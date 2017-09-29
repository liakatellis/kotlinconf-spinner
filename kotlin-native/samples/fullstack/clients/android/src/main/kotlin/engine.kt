/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import kotlinx.cinterop.*
import android.*
import kotlin.system.*

fun logError(message: String) {
    __android_log_write(ANDROID_LOG_ERROR, "KonanActivity", message)
}

fun logInfo(message: String) {
    __android_log_write(ANDROID_LOG_INFO, "KonanActivity", message)
}

val errno: Int
    get() = interop_errno()

fun getUnixError() = strerror(errno)!!.toKString()

const val LOOPER_ID_INPUT = 2

const val LOOPER_ID_SENSOR = 3

fun main(args: Array<String>) {
    logInfo("Hello world!")
    memScoped {
        val state = alloc<NativeActivityState>()
        getNativeActivityState(state.ptr)
        val engine = Engine(this, state)
        engine.mainLoop()
    }
}

fun <T : CPointed> CPointer<*>?.dereferenceAs(): T = this!!.reinterpret<T>().pointed

class Engine(val arena: NativePlacement, val state: NativeActivityState) {
    private val renderer = Renderer(arena, state.activity!!.pointed, state.savedState)
    private var queue: CPointer<AInputQueue>? = null
    private var sensorQueue: CPointer<ASensorEventQueue>? = null
    private var rendererState: COpaquePointer? = null

    private var currentPoint = Vector2.Zero
    private var startPoint = Vector2.Zero
    private var startTime = 0.0f
    private var animationEndTime = 0.0f
    private var velocity = Vector2.Zero
    private var acceleration = Vector2.Zero

    private var needRedraw = true
    private var animating = false

    fun initSensors() {
        val sensorManager = ASensorManager_getInstance()
        if (sensorQueue == null) {
            sensorQueue = ASensorManager_createEventQueue(
                    sensorManager, state.looper, LOOPER_ID_SENSOR, null /* no callback */, null /* no data */)
        }
        val sensor = ASensorManager_getDefaultSensor(sensorManager, ASENSOR_TYPE_ACCELEROMETER)
        if (sensor != null) {
            println("Accelerometer inited")
            ASensorEventQueue_enableSensor(sensorQueue, sensor)
            ASensorEventQueue_setEventRate(sensorQueue, sensor, 100000)
        } else {
            println("No accelerometer found")
        }
    }

    fun mainLoop() {
        initSensors()

        while (true) {
            // Process events.
            memScoped {
                val fd = alloc<IntVar>()
                eventLoop@while (true) {
                    val id = ALooper_pollAll(if (needRedraw || animating) 0 else -1, fd.ptr, null, null)
                    if (id < 0) break@eventLoop
                    when (id) {
                        LOOPER_ID_SYS -> {
                            if (!processSysEvent(fd))
                                return // An error occured.
                        }

                        LOOPER_ID_INPUT -> processUserInput()

                        LOOPER_ID_SENSOR -> processSensorInput()
                    }
                }
            }
            when {
                animating -> {
                    val elapsed = getTime() - startTime
                    if (elapsed >= animationEndTime) {
                        animating = false
                    } else {
                        move(startPoint + velocity * elapsed + acceleration * (elapsed * elapsed * 0.5f))
                        renderer.draw()
                    }
                }

                needRedraw -> renderer.draw()
            }
        }
    }

    val pointerSize = CPointerVar.size

    private fun processSysEvent(fd: IntVar): Boolean = memScoped {
        val eventPointer = alloc<COpaquePointerVar>()
        val readBytes = read(fd.value, eventPointer.ptr, pointerSize.narrow()).toLong()
        if (readBytes != pointerSize) {
            logError("Failure reading event, $readBytes read: ${getUnixError()}")
            return true
        }
        try {
            val event = eventPointer.value.dereferenceAs<NativeActivityEvent>()
            when (event.eventKind) {
                NativeActivityEventKind.START -> {
                    logInfo("START event received")
                    renderer.start()
                }

                NativeActivityEventKind.STOP -> renderer.stop()

                NativeActivityEventKind.DESTROY -> {
                    rendererState?.let { free(it) }
                    return false
                }

                NativeActivityEventKind.NATIVE_WINDOW_CREATED -> {
                    val windowEvent = eventPointer.value.dereferenceAs<NativeActivityWindowEvent>()
                    if (!renderer.initialize(windowEvent.window!!))
                        return false
                    logInfo("Renderer initialized")
                    renderer.draw()
                }

                NativeActivityEventKind.INPUT_QUEUE_CREATED -> {
                    val queueEvent = eventPointer.value.dereferenceAs<NativeActivityQueueEvent>()
                    if (queue != null)
                        AInputQueue_detachLooper(queue)
                    queue = queueEvent.queue
                    AInputQueue_attachLooper(queue, state.looper, LOOPER_ID_INPUT, null, null)
                }

                NativeActivityEventKind.INPUT_QUEUE_DESTROYED -> {
                    val queueEvent = eventPointer.value.dereferenceAs<NativeActivityQueueEvent>()
                    AInputQueue_detachLooper(queueEvent.queue)
                }

                NativeActivityEventKind.NATIVE_WINDOW_DESTROYED -> {
                    renderer.destroy()
                }

                NativeActivityEventKind.SAVE_INSTANCE_STATE -> {
                    val saveStateEvent = eventPointer.value.dereferenceAs<NativeActivitySaveStateEvent>()
                    val state = renderer.getState()
                    val dataSize = state.second.signExtend<size_t>()
                    rendererState = realloc(rendererState, dataSize)
                    memcpy(rendererState, state.first, dataSize)
                    saveStateEvent.savedState = rendererState
                    saveStateEvent.savedStateSize = dataSize
                }
            }
        } finally {
            notifySysEventProcessed()
        }
        return true
    }

    private fun getTime(): Float {
        memScoped {
            val now = alloc<timespec>()
            clock_gettime(CLOCK_MONOTONIC, now.ptr)
            return now.tv_sec + now.tv_nsec / 1_000_000_000.0f
        }
    }

    private fun getEventPoint(event: CPointer<AInputEvent>?, i: Int) =
            Vector2(AMotionEvent_getRawX(event, i.signExtend<size_t>()), AMotionEvent_getRawY(event, i.signExtend<size_t>()))

    private fun getEventTime(event: CPointer<AInputEvent>?) =
            AMotionEvent_getEventTime(event) / 1_000_000_000.0f

    private fun processUserInput(): Unit = memScoped {
        logInfo("Processing user input")
        val event = alloc<CPointerVar<AInputEvent>>()
        if (AInputQueue_getEvent(queue, event.ptr) < 0) {
            logError("Failure reading input event")
            return
        }
        val eventType = AInputEvent_getType(event.value)
        if (eventType == AINPUT_EVENT_TYPE_MOTION) {
            val action = AKeyEvent_getAction(event.value) and AMOTION_EVENT_ACTION_MASK
            when (action) {
                AMOTION_EVENT_ACTION_DOWN -> {
                    animating = false
                    currentPoint = getEventPoint(event.value, 0)
                    startTime = getEventTime(event.value)
                    startPoint = currentPoint
                }

                AMOTION_EVENT_ACTION_UP -> {
                    val endPoint = getEventPoint(event.value, 0)
                    val endTime = getEventTime(event.value)
                    animating = true
                    velocity = (endPoint - startPoint) / (endTime - startTime + 1e-9f)
                    val maxVelocityMagnitude = renderer.screen.length * 3.0f
                    if (velocity.length > maxVelocityMagnitude)
                        velocity = velocity * (maxVelocityMagnitude / velocity.length)
                    acceleration = velocity.normalized() * (-renderer.screen.length * 1.0f)
                    animationEndTime = velocity.length / acceleration.length
                    startPoint = endPoint
                    startTime = endTime
                    move(endPoint)
                }

                AMOTION_EVENT_ACTION_MOVE -> {
                    val numberOfPointers = AMotionEvent_getPointerCount(event.value).toInt()
                    for (i in 0 until numberOfPointers)
                        move(getEventPoint(event.value, i))
                }
            }
        }
        AInputQueue_finishEvent(queue, event.value, 1)
    }

    var shakeTimestamp: Long = 0

    private fun processSensorInput(): Unit = memScoped {
        if (sensorQueue == null) return
        val event = alloc<ASensorEvent>()
        val now = kotlin.system.getTimeMillis()
        while (ASensorEventQueue_getEvents(sensorQueue, event.ptr, 1) > 0) {
            val a = event.acceleration
            val force = sqrtf(a.x * a.x + a.y * a.y + a.z * a.z) / ASENSOR_STANDARD_GRAVITY
            if (force < 2.7f || shakeTimestamp + 1000 > now) {
                continue
            }
            shakeTimestamp = now
            animating = true
            velocity = Vector2(0.7071f, 0.7071f) * renderer.screen.length * 3.0f
            acceleration = velocity.normalized() * (-renderer.screen.length * 1.0f)
            animationEndTime = velocity.length / acceleration.length
            startTime = getTime()
            startPoint = currentPoint

        }
    }

    private fun move(newPoint: Vector2) {
        renderer.rotateBy(newPoint - currentPoint)
        currentPoint = newPoint
    }
}
