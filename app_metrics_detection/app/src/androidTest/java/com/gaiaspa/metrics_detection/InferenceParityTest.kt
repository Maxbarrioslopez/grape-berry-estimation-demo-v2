package com.gaiaspa.metrics_detection

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File
import android.util.Log

@RunWith(AndroidJUnit4::class)
class InferenceParityTest {
    @Test
    fun runBatchTest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val processor = BatchProcessor(context)
        
        val inputDir = "/sdcard/Download/opt_uvas_input"
        val outputDir = "/sdcard/Download/opt_uvas_output"
        
        Log.i("TEST", "Iniciando batch test desde $inputDir")
        val summary = processor.run(inputDir, outputDir)
        
        Log.i("TEST", "Test finalizado: ${summary.processedOk} OK, ${summary.processedError} ERR")
        Log.i("TEST", "Manifiesto en: ${summary.manifestPath}")
    }
}
