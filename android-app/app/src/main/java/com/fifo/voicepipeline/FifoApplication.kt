package com.fifo.voicepipeline

import android.app.Application
import com.fifo.voicepipeline.data.AppGraph

/**
 * Punto de entrada del proceso.
 *
 * Construye el [AppGraph] una sola vez en `onCreate`, de modo que la actividad,
 * el servicio en primer plano y los receivers lean la misma instancia de los
 * módulos. Ninguno de ellos debe volver a crear almacenamiento (ADR-0049).
 */
class FifoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.install(this)
    }
}
