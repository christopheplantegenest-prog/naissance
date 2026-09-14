package com.naissance.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Une trace perceptive : une empreinte acoustique et son histoire.
 * Aucune signification, aucune étiquette, aucun nom.
 */
class Trace(
    val id: Int,
    val empreinte: FloatArray,
    var force: Float,
    var renforcements: Int,
    var dernierRenforcement: Long,
    var dernierOubli: Long
)

/**
 * L'ÉTAT ACQUIS.
 * Volontairement séparé du moteur : cette classe ne sait rien du traitement
 * du signal, elle ne fait que conserver et restituer le vécu. Un moteur futur
 * pourra changer sans effacer ce qui a été accumulé ici.
 */
class MemoirePerceptive(private val fichier: File) {

    companion object {
        const val FORMAT = 1
        const val MAX_TRACES = 64
        const val DEMI_VIE_MS = 24L * 3600L * 1000L
        const val FORCE_MINIMALE = 0.05f
    }

    val traces = mutableListOf<Trace>()
    private var prochainId = 1
    var nbEvenements = 0
        private set

    fun charger() {
        traces.clear()
        prochainId = 1
        nbEvenements = 0
        if (!fichier.exists()) return
        try {
            val racine = JSONObject(fichier.readText())
            prochainId = racine.optInt("prochainId", 1)
            nbEvenements = racine.optInt("nbEvenements", 0)
            val tableau = racine.optJSONArray("traces") ?: return
            for (i in 0 until tableau.length()) {
                val o = tableau.getJSONObject(i)
                val e = o.getJSONArray("empreinte")
                val v = FloatArray(e.length())
                for (k in 0 until e.length()) v[k] = e.getDouble(k).toFloat()
                traces.add(
                    Trace(
                        o.getInt("id"),
                        v,
                        o.getDouble("force").toFloat(),
                        o.getInt("renforcements"),
                        o.optLong("dernierRenforcement", 0L),
                        o.optLong("dernierOubli", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            traces.clear()
        }
    }

    fun sauvegarder() {
        try {
            val tableau = JSONArray()
            for (t in traces) {
                val e = JSONArray()
                for (v in t.empreinte) e.put(v.toDouble())
                val o = JSONObject()
                o.put("id", t.id)
                o.put("empreinte", e)
                o.put("force", t.force.toDouble())
                o.put("renforcements", t.renforcements)
                o.put("dernierRenforcement", t.dernierRenforcement)
                o.put("dernierOubli", t.dernierOubli)
                tableau.put(o)
            }
            val racine = JSONObject()
            racine.put("format", FORMAT)
            racine.put("prochainId", prochainId)
            racine.put("nbEvenements", nbEvenements)
            racine.put("traces", tableau)
            fichier.writeText(racine.toString())
        } catch (e: Exception) {
            // une sauvegarde ratée ne doit jamais interrompre l'écoute
        }
    }

    fun effacer() {
        traces.clear()
        prochainId = 1
        nbEvenements = 0
        if (fichier.exists()) fichier.delete()
    }

    fun compterEvenement() {
        nbEvenements++
    }

    fun creer(empreinte: FloatArray, maintenant: Long): Trace {
        val t = Trace(prochainId++, empreinte, 0.5f, 0, maintenant, maintenant)
        traces.add(t)
        elaguer()
        return t
    }

    fun renforcer(t: Trace, empreinte: FloatArray, maintenant: Long) {
        // la trace glisse doucement vers la moyenne des expériences similaires
        val poids = 0.2f
        for (i in t.empreinte.indices) {
            if (i < empreinte.size) {
                t.empreinte[i] = t.empreinte[i] * (1f - poids) + empreinte[i] * poids
            }
        }
        t.renforcements++
        t.force = min(1f, t.force + 0.25f)
        t.dernierRenforcement = maintenant
    }

    /** Les traces faibles et anciennes s'effacent. La mémoire n'est pas infinie. */
    fun oublier(maintenant: Long) {
        val iter = traces.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (t.dernierOubli <= 0L) t.dernierOubli = maintenant
            val dt = maintenant - t.dernierOubli
            if (dt > 0) {
                t.force *= exp(-ln(2.0) * dt.toDouble() / DEMI_VIE_MS.toDouble()).toFloat()
                t.dernierOubli = maintenant
            }
            if (t.force < FORCE_MINIMALE) iter.remove()
        }
        elaguer()
    }

    private fun elaguer() {
        while (traces.size > MAX_TRACES) {
            var pire = 0
            for (i in traces.indices) if (traces[i].force < traces[pire].force) pire = i
            traces.removeAt(pire)
        }
    }
}

/** Ce qu'une expérience sonore produit comme observation. */
class Observation(
    val dureeMs: Int,
    val nouveaute: Float,
    val similarite: Float,
    val traceId: Int,
    val renforcements: Int,
    val traceNouvelle: Boolean,
    val nbTraces: Int
)

/**
 * LE MOTEUR.
 * Signal brut -> détection d'événement -> empreinte spectrale -> comparaison.
 * Aucune connaissance du langage, aucun modèle, aucun dictionnaire.
 */
class MoteurPerceptif(private val memoire: MemoirePerceptive) {

    companion object {
        const val TAUX = 16000
        const val TAILLE_BLOC = 512
        const val NB_BANDES = 16
        const val DUREE_MIN_BLOCS = 5
        const val DUREE_MAX_BLOCS = 94
        const val SILENCE_FIN_BLOCS = 6
        const val SEUIL_ASSOCIATION = 0.80f
    }

    var niveau = 0f
        private set
    var evenementEnCours = false
        private set

    private var plancher = 0.003f
    private var blocsEvenement = 0
    private var blocsSilence = 0
    private val cumulBandes = FloatArray(NB_BANDES)
    private var nbBlocsCumules = 0

    private val fenetre = FloatArray(TAILLE_BLOC) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (TAILLE_BLOC - 1))).toFloat()
    }
    private val bornes = IntArray(NB_BANDES + 1).also { b ->
        val fMin = 100.0
        val fMax = 7800.0
        for (i in 0..NB_BANDES) {
            val f = fMin * Math.pow(fMax / fMin, i.toDouble() / NB_BANDES)
            b[i] = (f * TAILLE_BLOC / TAUX).toInt().coerceIn(1, TAILLE_BLOC / 2 - 1)
        }
    }

    /** Appelé pour chaque bloc audio. Retourne une observation quand un événement se termine. */
    fun traiterBloc(echantillons: ShortArray, n: Int): Observation? {
        if (n < TAILLE_BLOC) return null

        var somme = 0.0
        for (i in 0 until TAILLE_BLOC) {
            val v = echantillons[i] / 32768.0
            somme += v * v
        }
        niveau = sqrt(somme / TAILLE_BLOC).toFloat()

        val seuilHaut = plancher * 4f + 0.006f
        val seuilBas = plancher * 2.2f + 0.003f

        if (!evenementEnCours) {
            plancher = max(0.0005f, plancher * 0.98f + niveau * 0.02f)
            if (niveau > seuilHaut) {
                evenementEnCours = true
                blocsEvenement = 0
                blocsSilence = 0
                nbBlocsCumules = 0
                java.util.Arrays.fill(cumulBandes, 0f)
            } else {
                return null
            }
        }

        blocsEvenement++
        accumulerSpectre(echantillons)

        blocsSilence = if (niveau < seuilBas) blocsSilence + 1 else 0

        if (blocsSilence >= SILENCE_FIN_BLOCS || blocsEvenement >= DUREE_MAX_BLOCS) {
            evenementEnCours = false
            val utiles = blocsEvenement - blocsSilence
            if (utiles < DUREE_MIN_BLOCS || nbBlocsCumules == 0) return null
            return conclure(blocsEvenement * TAILLE_BLOC * 1000 / TAUX)
        }
        return null
    }

    private fun accumulerSpectre(echantillons: ShortArray) {
        val re = FloatArray(TAILLE_BLOC)
        val im = FloatArray(TAILLE_BLOC)
        for (i in 0 until TAILLE_BLOC) re[i] = (echantillons[i] / 32768f) * fenetre[i]
        fft(re, im)
        for (b in 0 until NB_BANDES) {
            var e = 0f
            var c = 0
            for (k in bornes[b] until max(bornes[b] + 1, bornes[b + 1])) {
                e += re[k] * re[k] + im[k] * im[k]
                c++
            }
            if (c > 0) cumulBandes[b] += e / c
        }
        nbBlocsCumules++
    }

    private fun conclure(dureeMs: Int): Observation {
        val empreinte = FloatArray(NB_BANDES)
        for (b in 0 until NB_BANDES) {
            empreinte[b] = ln(1.0 + (cumulBandes[b] / nbBlocsCumules) * 1e6).toFloat()
        }
        // on retire le niveau moyen puis on normalise : seule la FORME du spectre compte,
        // pas le volume auquel le son a été produit
        var moyenne = 0f
        for (v in empreinte) moyenne += v
        moyenne /= NB_BANDES
        var norme = 0f
        for (i in empreinte.indices) {
            empreinte[i] -= moyenne
            norme += empreinte[i] * empreinte[i]
        }
        norme = sqrt(norme)
        if (norme > 1e-6f) for (i in empreinte.indices) empreinte[i] /= norme

        val maintenant = System.currentTimeMillis()
        memoire.oublier(maintenant)
        memoire.compterEvenement()

        var meilleure: Trace? = null
        var meilleurR = -1f
        for (t in memoire.traces) {
            if (t.empreinte.size != NB_BANDES) continue
            var r = 0f
            for (i in 0 until NB_BANDES) r += t.empreinte[i] * empreinte[i]
            if (r > meilleurR) { meilleurR = r; meilleure = t }
        }

        val obs: Observation
        if (meilleure != null && meilleurR >= SEUIL_ASSOCIATION) {
            val a
