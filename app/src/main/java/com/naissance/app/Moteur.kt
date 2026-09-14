package com.naissance.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

class Trace(
    val id: Int,
    val empreinte: FloatArray,
    var force: Float,
    var renforcements: Int,
    var dernierRenforcement: Long,
    var dernierOubli: Long
)

class MemoirePerceptive(private val fichier: File) {

    companion object {
        const val FORMAT = 1
        const val MAX_TRACES = 64
        const val DEMI_VIE_MS = 86400000L
        const val FORCE_MINIMALE = 0.05f
    }

    val traces = ArrayList<Trace>()
    private var prochainId = 1
    private var compteur = 0

    fun charger() {
        traces.clear()
        prochainId = 1
        compteur = 0
        if (!fichier.exists()) {
            return
        }
        try {
            val racine = JSONObject(fichier.readText())
            prochainId = racine.optInt("prochainId", 1)
            compteur = racine.optInt("nbEvenements", 0)
            val tableau = racine.optJSONArray("traces")
            if (tableau != null) {
                for (i in 0 until tableau.length()) {
                    val o = tableau.getJSONObject(i)
                    val e = o.getJSONArray("empreinte")
                    val v = FloatArray(e.length())
                    for (k in 0 until e.length()) {
                        v[k] = e.getDouble(k).toFloat()
                    }
                    val t = Trace(
                        o.getInt("id"),
                        v,
                        o.getDouble("force").toFloat(),
                        o.getInt("renforcements"),
                        o.optLong("dernierRenforcement", 0L),
                        o.optLong("dernierOubli", 0L)
                    )
                    traces.add(t)
                }
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
                for (v in t.empreinte) {
                    e.put(v.toDouble())
                }
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
            racine.put("nbEvenements", compteur)
            racine.put("traces", tableau)
            fichier.writeText(racine.toString())
        } catch (e: Exception) {
            return
        }
    }

    fun effacer() {
        traces.clear()
        prochainId = 1
        compteur = 0
        if (fichier.exists()) {
            fichier.delete()
        }
    }

    fun compterEvenement() {
        compteur = compteur + 1
    }

    fun creer(empreinte: FloatArray, maintenant: Long): Trace {
        val t = Trace(prochainId, empreinte, 0.5f, 0, maintenant, maintenant)
        prochainId = prochainId + 1
        traces.add(t)
        elaguer()
        return t
    }

    fun renforcer(t: Trace, empreinte: FloatArray, maintenant: Long) {
        val poids = 0.2f
        for (i in t.empreinte.indices) {
            if (i < empreinte.size) {
                t.empreinte[i] = t.empreinte[i] * (1f - poids) + empreinte[i] * poids
            }
        }
        t.renforcements = t.renforcements + 1
        var f = t.force + 0.25f
        if (f > 1f) {
            f = 1f
        }
        t.force = f
        t.dernierRenforcement = maintenant
    }

    fun oublier(maintenant: Long) {
        val restantes = ArrayList<Trace>()
        for (t in traces) {
            if (t.dernierOubli <= 0L) {
                t.dernierOubli = maintenant
            }
            val dt = maintenant - t.dernierOubli
            if (dt > 0L) {
                val facteur = exp(-0.6931471805599453 * dt.toDouble() / DEMI_VIE_MS.toDouble())
                t.force = t.force * facteur.toFloat()
                t.dernierOubli = maintenant
            }
            if (t.force >= FORCE_MINIMALE) {
                restantes.add(t)
            }
        }
        traces.clear()
        traces.addAll(restantes)
        elaguer()
    }

    private fun elaguer() {
        while (traces.size > MAX_TRACES) {
            var pire = 0
            for (i in traces.indices) {
                if (traces[i].force < traces[pire].force) {
                    pire = i
                }
            }
            traces.removeAt(pire)
        }
    }
}

class Observation(
    val dureeMs: Int,
    val nouveaute: Float,
    val similarite: Float,
    val traceId: Int,
    val renforcements: Int,
    val traceNouvelle: Boolean,
    val nbTraces: Int
)

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
    private val fenetre = FloatArray(TAILLE_BLOC)
    private val bornes = IntArray(NB_BANDES + 1)

    init {
        for (i in 0 until TAILLE_BLOC) {
            val x = 2.0 * PI * i.toDouble() / (TAILLE_BLOC - 1).toDouble()
            fenetre[i] = (0.5 - 0.5 * cos(x)).toFloat()
        }
        val fMin = 100.0
        val fMax = 7800.0
        for (i in 0..NB_BANDES) {
            val f = fMin * Math.pow(fMax / fMin, i.toDouble() / NB_BANDES.toDouble())
            var k = (f * TAILLE_BLOC.toDouble() / TAUX.toDouble()).toInt()
            if (k < 1) {
                k = 1
            }
            if (k > TAILLE_BLOC / 2 - 1) {
                k = TAILLE_BLOC / 2 - 1
            }
            bornes[i] = k
        }
    }

    fun traiterBloc(echantillons: ShortArray, n: Int): Observation? {
        if (n < TAILLE_BLOC) {
            return null
        }

        var somme = 0.0
        for (i in 0 until TAILLE_BLOC) {
            val v = echantillons[i].toDouble() / 32768.0
            somme = somme + v * v
        }
        niveau = sqrt(somme / TAILLE_BLOC.toDouble()).toFloat()

        val seuilHaut = plancher * 4f + 0.006f
        val seuilBas = plancher * 2.2f + 0.003f

        if (!evenementEnCours) {
            var p = plancher * 0.98f + niveau * 0.02f
            if (p < 0.0005f) {
                p = 0.0005f
            }
            plancher = p
            if (niveau <= seuilHaut) {
                return null
            }
            evenementEnCours = true
            blocsEvenement = 0
            blocsSilence = 0
            nbBlocsCumules = 0
            for (b in 0 until NB_BANDES) {
                cumulBandes[b] = 0f
            }
        }

        blocsEvenement = blocsEvenement + 1
        accumulerSpectre(echantillons)

        if (niveau < seuilBas) {
            blocsSilence = blocsSilence + 1
        } else {
            blocsSilence = 0
        }

        if (blocsSilence >= SILENCE_FIN_BLOCS || blocsEvenement >= DUREE_MAX_BLOCS) {
            evenementEnCours = false
            val utiles = blocsEvenement - blocsSilence
            if (utiles < DUREE_MIN_BLOCS || nbBlocsCumules == 0) {
                return null
            }
            val duree = blocsEvenement * TAILLE_BLOC * 1000 / TAUX
            return conclure(duree)
        }
        return null
    }

    private fun accumulerSpectre(echantillons: ShortArray) {
        val re = FloatArray(TAILLE_BLOC)
        val im = FloatArray(TAILLE_BLOC)
        for (i in 0 until TAILLE_BLOC) {
            re[i] = (echantillons[i].toFloat() / 32768f) * fenetre[i]
        }
        fft(re, im)
        for (b in 0 until NB_BANDES) {
            var e = 0f
            var c = 0
            var fin = bornes[b + 1]
            if (fin <= bornes[b]) {
                fin = bornes[b] + 1
            }
            for (k in bornes[b] until fin) {
                e = e + re[k] * re[k] + im[k] * im[k]
                c = c + 1
            }
            if (c > 0) {
                cumulBandes[b] = cumulBandes[b] + e / c.toFloat()
            }
        }
        nbBlocsCumules = nbBlocsCumules + 1
    }

    private fun conclure(dureeMs: Int): Observation {
        val empreinte = FloatArray(NB_BANDES)
        for (b in 0 until NB_BANDES) {
            val moyBande = cumulBandes[b] / nbBlocsCumules.toFloat()
            empreinte[b] = ln(1.0 + moyBande.toDouble() * 1000000.0).toFloat()
        }

        var moyenne = 0f
        for (v in empreinte) {
            moyenne = moyenne + v
        }
        moyenne = moyenne / NB_BANDES.toFloat()

        var norme = 0f
        for (i in empreinte.indices) {
            empreinte[i] = empreinte[i] - moyenne
            norme = norme + empreinte[i] * empreinte[i]
        }
        norme = sqrt(norme.toDouble()).toFloat()
        if (norme > 0.000001f) {
            for (i in empreinte.indices) {
                empreinte[i] = empreinte[i] / norme
            }
        }

        val maintenant = System.currentTimeMillis()
        memoire.oublier(maintenant)
        memoire.compterEvenement()

        var meilleure: Trace? = null
        var meilleurR = -1f
        for (t in memoire.traces) {
            if (t.empreinte.size == NB_BANDES) {
                var r = 0f
                for (i in 0 until NB_BANDES) {
                    r = r + t.empreinte[i] * empreinte[i]
                }
                if (r > meilleurR) {
                    meilleurR = r
                    meilleure = t
                }
            }
        }

        val connue = meilleure
        if (connue != null && meilleurR >= SEUIL_ASSOCIATION) {
            val anciens = connue.renforcements
            val familiarite = 1f - exp(-anciens.toDouble() / 3.0).toFloat()
            var nouv = (1f - meilleurR) * 0.5f + (1f - familiarite) * 0.5f
            if (nouv < 0f) {
                nouv = 0f
            }
            if (nouv > 1f) {
                nouv = 1f
            }
            memoire.renforcer(connue, empreinte, maintenant)
            memoire.sauvegarder()
            return Observation(
                dureeMs,
                nouv,
                meilleurR,
                connue.id,
                connue.renforcements,
                false,
                memoire.traces.size
            )
        }

        var sim = meilleurR
        if (sim < 0f) {
            sim = 0f
        }
        var nouv = 1f - sim
        if (nouv < 0f) {
            nouv = 0f
        }
        if (nouv > 1f) {
            nouv = 1f
        }
        val neuve = memoire.creer(empreinte, maintenant)
        memoire.sauvegarder()
        return Observation(
            dureeMs,
            nouv,
            sim,
            neuve.id,
            neuve.renforcements,
            true,
            memoire.traces.size
        )
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len.toDouble()
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                var k = 0
                while (k < len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val pr = re[i + k + len / 2]
                    val pi2 = im[i + k + len / 2]
                    val vr = pr * cr - pi2 * ci
                    val vi = pr * ci + pi2 * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                    k = k + 1
                }
                i = i + len
            }
            len = len shl 1
        }
    }
}
