package com.kanade.recorder.Utils

import android.graphics.Rect
import android.hardware.Camera
import android.util.Log
import android.view.SurfaceHolder
import com.kanade.recorder._interface.ICameraManager
import java.util.*
import java.util.concurrent.locks.ReentrantLock

@Suppress("DEPRECATION")
class CameraManager : ICameraManager, Camera.AutoFocusCallback {
    private val TAG = "CameraManager"

    private val lock = ReentrantLock()
    private lateinit var holder: SurfaceHolder
    private lateinit var camera: Camera
    private lateinit var params: Camera.Parameters
    private val sizeComparator by lazy { CameraSizeComparator() }

    private var isRelease = true
    private var isPreview = false
    private var svWidth: Int = 0
    private var svHeight: Int = 0
    private var initWidth: Int = 0
    private var initHeight: Int = 0

    override fun init(holder: SurfaceHolder) {
        this.holder = holder
        if (isPreview) camera.setPreviewDisplay(holder)
    }

    override fun init(holder: SurfaceHolder, width: Int, height: Int) {
        this.initWidth = width
        this.initHeight = height
        init(holder)
    }

    override fun connectCamera() {
        if (isPreview) return
        try {
            isRelease = false
            isPreview = true
            camera = Camera.open(0)
            params = camera.parameters
            setParams(holder, params, initWidth, initHeight)
            camera.parameters = params
            camera.setDisplayOrientation(90)
            camera.startPreview()
        } catch (e: Exception) {
//            camera.release()
        }
    }

    fun getCamera(): Camera = camera

    override fun releaseCamera() {
        if (!isRelease) {
            camera.stopPreview()
            camera.release()
            isRelease = true
            isPreview = false
            Log.d(TAG, "camera has release")
        }
    }

    override fun onAutoFocus(success: Boolean, camera: Camera) {
        if (success) camera.cancelAutoFocus()
    }

    /**
     * @return first => width, second => height
     */
    override fun getVideoSize(): Pair<Int, Int> = Pair(svWidth, svHeight)

    /**
     * 对焦
     * @param x
     * *
     * @param y
     */
     override fun handleFocusMetering(x: Float, y: Float) {
        lock(lock, {
            if (!isPreview || isRelease) return@lock
            val focusRect = calculateTapArea(x, y, 1f)
            val meteringRect = calculateTapArea(x, y, 1.5f)

            params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO

            if (params.maxNumFocusAreas > 0) {
                val focusAreas = ArrayList<Camera.Area>()
                focusAreas.add(Camera.Area(focusRect, 1000))

                params.focusAreas = focusAreas
            }

            if (params.maxNumMeteringAreas > 0) {
                val meteringAreas = ArrayList<Camera.Area>()
                meteringAreas.add(Camera.Area(meteringRect, 1000))

                params.meteringAreas = meteringAreas
            }

            try {
                camera.parameters = params
                camera.autoFocus(this)
            } catch (e: Exception) {
                Log.d(TAG, "focus error")
                e.printStackTrace()
            }
        })
    }

    /**
     * 缩放
     * @param isZoomIn
     */
    override fun handleZoom(isZoomIn: Boolean) {
        lock(lock, {
            if (!isPreview || isRelease || !params.isZoomSupported) return@lock
            val maxZoom = params.maxZoom
            var zoom = params.zoom
            if (isZoomIn && zoom < maxZoom) {
                zoom++
            } else if (zoom > 0) {
                zoom--
            }

            params.zoom = zoom

            try {
                camera.parameters = params
            } catch (e: Exception) {
                Log.d(TAG, "zoom error")
                e.printStackTrace()
            }
        })
    }

    override fun handleZoom(zoom: Int) {
        lock(lock, {
            if (!isPreview || isRelease || !params.isZoomSupported) return@lock
            val maxZoom = params.maxZoom
            params.zoom = Math.min(zoom, maxZoom)
            try {
                camera.parameters = params
            } catch (e: Exception) {
                Log.d(TAG, "zoom error")
                e.printStackTrace()
            }
        })
    }

    private fun setParams(holder: SurfaceHolder, params: Camera.Parameters, width: Int, height: Int) {
        // 获取最合适的视频尺寸(预览和录像)
        val supportPreviewSizes = params.supportedPreviewSizes
        val screenProp = height / width.toFloat()
        val optimalSize = getBestSize(supportPreviewSizes, 1000, screenProp)
        this.svWidth = optimalSize.width
        this.svHeight = optimalSize.height

        // 设置holder
        holder.setFixedSize(svWidth, svHeight)
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

        // 设置params
        params.zoom = 1
        params.setPreviewSize(svWidth, svHeight)
        params.setPictureSize(svWidth, svHeight)
        val supportFocusModes = params.supportedFocusModes
        supportFocusModes.indices
                .filter { supportFocusModes[it] == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO }
                .forEach { params.focusMode = supportFocusModes[it] }
    }

    private fun getBestSize(list: List<Camera.Size>, th: Int, rate: Float): Camera.Size {
        Collections.sort(list, sizeComparator)
        var i = 0
        for (s in list) {
            if (s.width > th && equalRate(s, rate)) {
                Log.i(TAG, "MakeSure Preview :w = " + s.width + " h = " + s.height)
                break
            }
            i++
        }
        if (i == list.size) {
            return getBestSize(list, rate)
        } else {
            return list[i]
        }
    }

    private fun getBestSize(list: List<Camera.Size>, rate: Float): Camera.Size {
        var previewDisparity = 100f
        var index = 0
        for (i in list.indices) {
            val cur = list[i]
            val prop = cur.width.toFloat() / cur.height.toFloat()
            if (Math.abs(rate - prop) < previewDisparity) {
                previewDisparity = Math.abs(rate - prop)
                index = i
            }
        }
        return list[index]
    }


    private fun equalRate(s: Camera.Size, rate: Float): Boolean {
        val r = s.width.toFloat() / s.height.toFloat()
        return Math.abs(r - rate) <= 0.2
    }

    /**
     * Convert touch position x:y to [Camera.Area] position -1000:-1000 to 1000:1000.
     * 注意这里长边是width
     */
    private fun calculateTapArea(x: Float, y: Float, coefficient: Float): Rect {
        val focusAreaSize = 300f
        val areaSize = java.lang.Float.valueOf(focusAreaSize * coefficient)!!.toInt()

        val left = clamp((x / svWidth * 2000 - 1000 - areaSize / 2).toInt(), -1000, 1000)
        val right = clamp(left + areaSize, -1000, 1000)
        val top = clamp((y / svHeight * 2000 - 1000 - areaSize / 2).toInt(), -1000, 1000)
        val bottom = clamp(top + areaSize, -1000, 1000)

        return Rect(left, top, right, bottom)
    }

    private fun clamp(x: Int, min: Int, max: Int): Int {
        if (x > max) {
            return max
        }
        if (x < min) {
            return min
        }
        return x
    }

    private inner class CameraSizeComparator : Comparator<Camera.Size> {
        override fun compare(lhs: Camera.Size, rhs: Camera.Size): Int {
            if (lhs.width == rhs.width) {
                return 0
            } else if (lhs.width > rhs.width) {
                return 1
            } else {
                return -1
            }
        }
    }
}