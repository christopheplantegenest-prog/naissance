package com.naissance.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var etatEcoute: TextView
    private lateinit var niveauSonore: TextView
    private lateinit var evenement: TextView
    private lateinit var nouveaute: TextView
    private lateinit var trace: TextView
    private lateinit var nbTraces: TextView
    private lateinit var journal: TextView
    private lateinit var boutonEcoute: Button
    private lateinit var boutonEffacer: Button

    private lateinit var memoire: MemoirePerceptive
    private lateinit var moteur: MoteurPerceptif

    private var enregistreur: AudioRecord? = null
    @Volatile private var enEcoute = false
    private val ui = Handler(Looper.getMainLooper())
    private val lignes = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etatEcoute = findViewById(R.id.etatEcoute)
        niveauSonore = findViewById(R.id.niveauSonore)
        evenement = findViewById(R.id.evenement)
        nouveaute = findViewById(R.id.nouveaute)
        trace = findViewById(R.id.trace)
        nbTraces = findViewById(R.id.nbTraces)
        journal = findViewById(R.id.journal)
        boutonEcoute = findViewById(R.id.boutonEcoute)
        boutonEffacer = findViewById(R.id.boutonEffacer)

        // l'état acquis vit dans son propre fichier, indépendant du moteur
        memoire = MemoirePerceptive(File(filesDir, "etat_perceptif.json"))
        memoire.charger()
        moteur = MoteurPerceptif(memoire)
        nbTraces.text = "Traces conservées : ${memoire.traces.size}"

        boutonEcoute.setOnClickListener {
            if (enEcoute) arreterEcoute() else demanderPuisDemarrer()
        }

        boutonEffacer.setOnClickListener {
            memoire.effacer()
            lignes.clear()
            journal.text = ""
            evenement.text = "Événement : aucun"
            nouveaute.text = "Nouveauté : —"
            trace.text = "Trace : —"
            nbTraces.text = "Traces conservées : 0"
        }
    }

    private fun demanderPuisDemarrer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            demarrerEcoute()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            demarrerEcoute()
        } else {
            etatEcoute.text = "Écoute : micro refusé"
        }
    }

    private fun demarrerEcoute() {
        val taille = maxOf(
            AudioRecord.getMinBufferSize(
                MoteurPerceptif.TAUX,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            MoteurPerceptif.TAILLE_BLOC * 4
        )

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                MoteurPerceptif.TAUX,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                taille
            )
        } catch (e: SecurityException) {
            etatEcoute.text = "Écoute : micro indisponible"
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            etatEcoute.text = "Écoute : micro indisponible"
            rec.release()
            return
        }

        enregistreur = rec
        rec.startRecording()
        enEcoute = true
        etatEcoute.text = "Écoute : ACTIVE"
        boutonEcoute.text = "Arrêter l'écoute"

        thread {
            val tampon = ShortArray(MoteurPerceptif.TAILLE_BLOC)
            while (enEcoute) {
                val lus = rec.read(tampon, 0, tampon.size)
                if (lus <= 0) continue
                val obs = moteur.traiterBloc(tampon, lus)
                val n = moteur.niveau
                val enCours = moteur.evenementEnCours
                ui.post {
                    niveauSonore.text = "Niveau : ${"%.4f".format(n)}"
                    evenement.text = if (enCours) "Événement : EN COURS" else "Événement : —"
                }
                if (obs != null) ui.post { afficher(obs) }
            }
        }
    }

    private fun afficher(o: Observation) {
        nouveaute.text = "Nouveauté : ${"%.2f".format(o.nouveaute)}"
        trace.text = if (o.traceNouvelle) {
            "Trace : #${o.traceId} (nouvelle) — renforcements ${o.renforcements}"
        } else {
            "Trace : #${o.traceId} — renforcements ${o.renforcements} — similarité ${"%.2f".format(o.similarite)}"
        }
        nbTraces.text = "Traces conservées : ${o.nbTraces}"

        val marque = if (o.traceNouvelle) "NOUVELLE" else "revue"
        lignes.add(0, "#${o.traceId} $marque  nouv ${"%.2f".format(o.nouveaute)}  sim ${"%.2f".format(o.similarite)}  r${o.renforcements}  ${o.dureeMs}ms")
        while (lignes.size > 15) lignes.removeAt(lignes.size - 1)
        journal.text = lignes.joinToString("\n")
    }

    private fun arreterEcoute() {
        enEcoute = false
        try {
            enregistreur?.stop()
        } catch (e: Exception) {
        }
        enregistreur?.release()
        enregistreur = null
        etatEcoute.text = "Écoute : inactive"
        boutonEcoute.text = "Démarrer l'écoute"
        evenement.text = "Événement : —"
        memoire.sauvegarder()
    }

    override fun onPause() {
        super.onPause()
        if (enEcoute) arreterEcoute()
    }
}
