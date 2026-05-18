/*
 * Copyright 2021 Shubham Panchal
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package com.gaiaspa.metrics_detection.depth_estimation

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Visual-only depth-map overlay.
 *
 * Renders the depth estimation bitmap over the camera PreviewView.
 * This is a presentation-only layer and must not alter any prediction
 * data, quantities, calPredicts, or backend payloads.
 *
 * See also: activity_main.xml, FrameAnalyser.kt, MainActivity.kt
 */
class DrawingOverlay(context : Context, attributeSet : AttributeSet) : SurfaceView( context , attributeSet ) , SurfaceHolder.Callback {

    // This variable is assigned in FrameAnalyser.kt
    var depthMaskBitmap : Bitmap? = null

    // These variables are assigned in MainActivity.kt
    var isFrontCameraOn = true
    var isShowingDepthMap = false



    override fun surfaceCreated(holder: SurfaceHolder) {
    }


    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }


    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }}

