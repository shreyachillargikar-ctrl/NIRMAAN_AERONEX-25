package com.example.camerax

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Pass application context to TFLiteHelper safely
    private val tfliteHelper = TFLiteHelper(application)

    private val _predictionResult = MutableStateFlow("Press button to predict")
    val predictionResult: StateFlow<String> = _predictionResult

    fun runInference(inputData: Array<FloatArray>) {
        val result = tfliteHelper.predict(inputData)
        _predictionResult.value = "Result: ${result[0][0]}"
    }

    override fun onCleared() {
        super.onCleared()
        tfliteHelper.close() // Safely close model when ViewModel is destroyed
    }
}