package com.mrousavy.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.facebook.react.uimanager.UIManagerHelper
import com.mrousavy.camera.frameprocessor.VisionCameraInstaller
import com.mrousavy.camera.frameprocessor.VisionCameraProxy
import com.mrousavy.camera.parsers.*
import com.mrousavy.camera.utils.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@ReactModule(name = CameraViewModule.TAG)
@Suppress("unused")
class CameraViewModule(reactContext: ReactApplicationContext): ReactContextBaseJavaModule(reactContext) {
  companion object {
    const val TAG = "CameraView"
    var RequestCode = 10
  }

  private val coroutineScope = CoroutineScope(Dispatchers.Default) // TODO: or Dispatchers.Main?

  override fun invalidate() {
    super.invalidate()
    if (coroutineScope.isActive) {
      coroutineScope.cancel("CameraViewModule has been destroyed.")
    }
  }

  override fun getName(): String {
    return TAG
  }

  private suspend fun findCameraView(viewId: Int): CameraView {
    return suspendCoroutine { continuation ->
      UiThreadUtil.runOnUiThread {
        Log.d(TAG, "Finding view $viewId...")
        val view = if (reactApplicationContext != null) UIManagerHelper.getUIManager(reactApplicationContext, viewId)?.resolveView(viewId) as CameraView? else null
        Log.d(TAG,  if (reactApplicationContext != null) "Found view $viewId!" else "Couldn't find view $viewId!")
        if (view != null) continuation.resume(view)
        else continuation.resumeWithException(ViewNotFoundError(viewId))
      }
    }
  }

  @ReactMethod(isBlockingSynchronousMethod = true)
  fun installFrameProcessorBindings(): Boolean {
    return try {
      val proxy = VisionCameraProxy(reactApplicationContext)
      VisionCameraInstaller.install(proxy)
      true
    } catch (e: Error) {
      Log.e(TAG, "Failed to install Frame Processor JSI Bindings!", e)
      false
    }
  }

  @ReactMethod
  fun takePhoto(viewTag: Int, options: ReadableMap, promise: Promise) {
    coroutineScope.launch {
      val view = findCameraView(viewTag)
      withPromise(promise) {
        view.takePhoto(options)
      }
    }
  }

  @Suppress("unused")
  @ReactMethod
  fun takeSnapshot(viewTag: Int, options: ReadableMap, promise: Promise) {
    coroutineScope.launch {
      val view = findCameraView(viewTag)
      withPromise(promise) {
        view.takeSnapshot(options)
      }
    }
  }

  // TODO: startRecording() cannot be awaited, because I can't have a Promise and a onRecordedCallback in the same function. Hopefully TurboModules allows that
  @ReactMethod
  fun startRecording(viewTag: Int, options: ReadableMap, onRecordCallback: Callback) {
    coroutineScope.launch {
      val view = findCameraView(viewTag)
      try {
        view.startRecording(options, onRecordCallback)
      } catch (error: CameraError) {
        val map = makeErrorMap("${error.domain}/${error.id}", error.message, error)
        onRecordCallback(null, map)
      } catch (error: Throwable) {
        val map = makeErrorMap("capture/unknown", "An unknown error occurred while trying to start a video recording!", error)
        onRecordCallback(null, map)
      }
    }
  }

  @ReactMethod
  fun pauseRecording(viewTag: Int, promise: Promise) {
    coroutineScope.launch {
      withPromise(promise) {
        val view = findCameraView(viewTag)
        view.pauseRecording()
        return@withPromise null
      }
    }
  }

  @ReactMethod
  fun resumeRecording(viewTag: Int, promise: Promise) {
    coroutineScope.launch {
      val view = findCameraView(viewTag)
      withPromise(promise) {
        view.resumeRecording()
        return@withPromise null
      }
    }
  }

  @ReactMethod
  fun stopRecording(viewTag: Int, promise: Promise) {
    coroutineScope.launch {
      val view = findCameraView(viewTag)
      withPromise(promise) {
        view.stopRecording()
        return@withPromise null
      }
    }
  }

  @ReactMethod
  fun focus(viewTag: Int, point: ReadableMap, promise: Promise) {
    coroutineScope.launch {
      val view = findCameraView(viewTag)
      withPromise(promise) {
        view.focus(point)
        return@withPromise null
      }
    }
  }

  @ReactMethod
  fun getAvailableCameraDevices(promise: Promise) {
    coroutineScope.launch {
      withPromise(promise) {
        val manager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val devices = Arguments.createArray()
        manager.cameraIdList.forEach { cameraId ->
          val device = CameraDeviceDetails(manager, cameraId)
          devices.pushMap(device.toMap())
        }
        promise.resolve(devices)
      }
    }
  }

  private fun canRequestPermission(permission: String): Boolean {
    val activity = currentActivity as? PermissionAwareActivity
    return activity?.shouldShowRequestPermissionRationale(permission) ?: false
  }

  @ReactMethod
  fun getCameraPermissionStatus(promise: Promise) {
    val status = ContextCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.CAMERA)
    var parsed = PermissionStatus.fromPermissionStatus(status)
    if (parsed == PermissionStatus.DENIED && canRequestPermission(Manifest.permission.CAMERA)) {
      parsed = PermissionStatus.NOT_DETERMINED
    }
    promise.resolve(parsed.unionValue)
  }

  @ReactMethod
  fun getMicrophonePermissionStatus(promise: Promise) {
    val status = ContextCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.RECORD_AUDIO)
    var parsed = PermissionStatus.fromPermissionStatus(status)
    if (parsed == PermissionStatus.DENIED && canRequestPermission(Manifest.permission.RECORD_AUDIO)) {
      parsed = PermissionStatus.NOT_DETERMINED
    }
    promise.resolve(parsed.unionValue)
  }

  @ReactMethod
  fun requestCameraPermission(promise: Promise) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      // API 21 and below always grants permission on app install
      return promise.resolve(PermissionStatus.GRANTED.unionValue)
    }

    val activity = reactApplicationContext.currentActivity
    if (activity is PermissionAwareActivity) {
      val currentRequestCode = RequestCode++
      val listener = PermissionListener { requestCode: Int, _: Array<String>, grantResults: IntArray ->
        if (requestCode == currentRequestCode) {
          val permissionStatus = if (grantResults.isNotEmpty()) grantResults[0] else PackageManager.PERMISSION_DENIED
          val parsed = PermissionStatus.fromPermissionStatus(permissionStatus)
          promise.resolve(parsed.unionValue)
          return@PermissionListener true
        }
        return@PermissionListener false
      }
      activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), currentRequestCode, listener)
    } else {
      promise.reject("NO_ACTIVITY", "No PermissionAwareActivity was found! Make sure the app has launched before calling this function.")
    }
  }

  @ReactMethod
  fun requestMicrophonePermission(promise: Promise) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      // API 21 and below always grants permission on app install
      return promise.resolve(PermissionStatus.GRANTED.unionValue)
    }

    val activity = reactApplicationContext.currentActivity
    if (activity is PermissionAwareActivity) {
      val currentRequestCode = RequestCode++
      val listener = PermissionListener { requestCode: Int, _: Array<String>, grantResults: IntArray ->
        if (requestCode == currentRequestCode) {
          val permissionStatus = if (grantResults.isNotEmpty()) grantResults[0] else PackageManager.PERMISSION_DENIED
          val parsed = PermissionStatus.fromPermissionStatus(permissionStatus)
          promise.resolve(parsed.unionValue)
          return@PermissionListener true
        }
        return@PermissionListener false
      }
      activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), currentRequestCode, listener)
    } else {
      promise.reject("NO_ACTIVITY", "No PermissionAwareActivity was found! Make sure the app has launched before calling this function.")
    }
  }
}
