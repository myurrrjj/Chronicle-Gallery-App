package com.myurrrjj.chronicle

/*
 * Face Sorting Lab — visual edition
 * -----------------------------------------------------------------------------
 * A labelled, ground-truth benchmark for the Chronicle face-sorting pipeline,
 * presented as photographs and face crops rather than as numbers.
 *
 * Pipeline (identical to production):
 *   MlKitFaceDetector (accurate, all landmarks)  ->  validation filters
 *   ->  FaceCropper (5-point ArcFace alignment)  ->  SFace 128-d embedding
 *   ->  greedy FaceClusterer loop, replayed at several MATCHING_THRESHOLDs.
 *
 * Every face that reaches the embedder is written to disk as a JPEG crop, so the
 * report can show you exactly which face went where.
 *
 * Nothing in this file writes to the production face index.
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.myurrrjj.chronicle.core.ml.FaceCropper
import com.myurrrjj.chronicle.core.ml.FaceEmbedder
import com.myurrrjj.chronicle.core.ml.MlKitFaceDetector
import com.myurrrjj.chronicle.core.util.BitmapLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import kotlin.collections.iterator
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

// =============================================================================
// 1. THE TEST PLAN
// =============================================================================

const val LAB_TARGET_PHOTOS_PER_CATEGORY = 15

val LAB_PRESET_THRESHOLDS: List<Float> = listOf(0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.50f)

const val LAB_MIN_THRESHOLD = 0.25f
const val LAB_MAX_THRESHOLD = 0.50f

private const val CROP_DIR_NAME = "face_lab_crops"

enum class LabCategoryKind { SINGLE, MIXED }

data class LabBlock(
    val id: String,
    val title: String,
    val personIds: List<Int>
)

data class LabCategory(
    val id: String,
    val kind: LabCategoryKind,
    val people: List<Int>,
    val blockId: String,
    val title: String
) {
    val personLabel: String
        get() = if (people.size == 1) "Person ${people.first()}"
        else people.joinToString(" + ") { "Person $it" }

    val instruction: String
        get() = when (kind) {
            LabCategoryKind.SINGLE ->
                "Photos of Person ${people.first()} only."

            LabCategoryKind.MIXED ->
                "Exactly ${personLabel} in frame, nobody else."
        }
}

private fun soloCategory(person: Int, block: String): LabCategory = LabCategory(
    id = "solo_$person",
    kind = LabCategoryKind.SINGLE,
    people = listOf(person),
    blockId = block,
    title = "Person $person — alone"
)

private fun mixedCategory(block: String, people: List<Int>): LabCategory = LabCategory(
    id = "mix_" + people.sorted().joinToString("_"),
    kind = LabCategoryKind.MIXED,
    people = people.sorted(),
    blockId = block,
    title = people.sorted().joinToString(" + ") { "P$it" }
)

val LAB_BLOCKS: List<LabBlock> = listOf(
    LabBlock("A", "Block A — People 1 to 6", listOf(1, 2, 3, 4, 5, 6)),
    LabBlock("B", "Block B — People 7 to 12", listOf(7, 8, 9, 10, 11, 12))
)

val LAB_PEOPLE: List<Int> = (1..12).toList()

/** 18 categories: 12 single-person blocks and 6 combination blocks. */
val LAB_CATEGORIES: List<LabCategory> = listOf(
    soloCategory(1, "A"),
    soloCategory(2, "A"),
    soloCategory(3, "A"),
    soloCategory(4, "A"),
    soloCategory(5, "A"),
    soloCategory(6, "A"),
    mixedCategory("A", listOf(1, 2)),
    mixedCategory("A", listOf(3, 4, 5)),
    mixedCategory("A", listOf(1, 2, 3, 4)),
    soloCategory(7, "B"),
    soloCategory(8, "B"),
    soloCategory(9, "B"),
    soloCategory(10, "B"),
    soloCategory(11, "B"),
    soloCategory(12, "B"),
    mixedCategory("B", listOf(7, 8)),
    mixedCategory("B", listOf(9, 10, 11)),
    mixedCategory("B", listOf(9, 10, 11, 12))
)

val LAB_CATEGORY_MAP: Map<String, LabCategory> = LAB_CATEGORIES.associateBy { it.id }

// =============================================================================
// 2. DATASET MODEL + PERSISTENCE
// =============================================================================

data class LabPhoto(
    val id: String,
    val uri: Uri,
    val categoryId: String,
    val displayName: String
)

data class RejectedFace(
    val detectionIndex: Int,
    val reasons: List<String>,
    val boxWidth: Int,
    val boxHeight: Int
)

data class LabPhotoReport(
    val photoId: String,
    val displayName: String,
    val categoryId: String,
    val rawDetections: Int,
    val acceptedDetections: Int,
    val embeddedFaces: Int,
    val rejected: List<RejectedFace>,
    val imageWidth: Int,
    val imageHeight: Int,
    val error: String? = null,
    val note: String? = null
)

/**
 * One face that made it through the whole pipeline and holds an embedding.
 *
 * [knownPerson] is set only when the identity is *certain*: a single-person
 * category whose photo produced exactly one accepted detection.
 */
data class LabFace(
    val photoIndex: Int,
    val photoName: String,
    val categoryId: String,
    val detectionIndex: Int,
    val embedding: FloatArray,
    val boxLeft: Int,
    val boxTop: Int,
    val boxRight: Int,
    val boxBottom: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val cropPath: String?,
    val knownPerson: Int?,
    val allowedPeople: Set<Int>,
    val engineIndex: Int = -1
) {
    val leftFraction: Float get() = if (imageWidth > 0) boxLeft.toFloat() / imageWidth else 0f
    val topFraction: Float get() = if (imageHeight > 0) boxTop.toFloat() / imageHeight else 0f
    val rightFraction: Float get() = if (imageWidth > 0) boxRight.toFloat() / imageWidth else 1f
    val bottomFraction: Float get() = if (imageHeight > 0) boxBottom.toFloat() / imageHeight else 1f
    val boxWidth: Int get() = boxRight - boxLeft
    val boxHeight: Int get() = boxBottom - boxTop
}

private class LabDatasetStore(context: Context) {

    private val prefs = context.getSharedPreferences("chronicle_face_sorting_lab", Context.MODE_PRIVATE)

    fun loadPhotos(): List<LabPhoto> {
        val raw = prefs.getString(KEY_PHOTOS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<LabPhoto>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val uri = obj.optString("uri", "")
                val categoryId = obj.optString("category", "")
                if (uri.isEmpty() || categoryId.isEmpty()) continue
                if (LAB_CATEGORY_MAP[categoryId] == null) continue
                out.add(
                    LabPhoto(
                        id = uri,
                        uri = Uri.parse(uri),
                        categoryId = categoryId,
                        displayName = obj.optString("name", "Photo")
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun savePhotos(photos: List<LabPhoto>) {
        try {
            val array = JSONArray()
            photos.forEach { photo ->
                array.put(
                    JSONObject()
                        .put("uri", photo.uri.toString())
                        .put("category", photo.categoryId)
                        .put("name", photo.displayName)
                )
            }
            prefs.edit().putString(KEY_PHOTOS, array.toString()).apply()
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    fun loadThreshold(): Float = prefs.getFloat(KEY_THRESHOLD, 0.35f)

    fun saveThreshold(value: Float) {
        prefs.edit().putFloat(KEY_THRESHOLD, value).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_PHOTOS = "photos_v1"
        const val KEY_THRESHOLD = "threshold_v1"
    }
}

// =============================================================================
// 3. RESULT MODEL
// =============================================================================

data class PersonCount(val person: Int, val count: Int)

data class GroupCount(val group: Int, val count: Int)

data class GroupInfo(
    val index: Int,
    val faceCount: Int,
    val knownCounts: List<PersonCount>,
    val unknownCount: Int,
    val inferredPerson: Int?,
    val ambiguous: Boolean
) {
    val isMerged: Boolean get() = knownCounts.size >= 2

    val compositionLabel: String
        get() = when {
            knownCounts.isEmpty() -> "unidentified"
            else -> knownCounts.joinToString(" + ") { "P${it.person} ×${it.count}" }
        }
}

data class PersonOutcome(
    val person: Int,
    val faces: Int,
    val groupCounts: List<GroupCount>
) {
    val groupCount: Int get() = groupCounts.size
    val dominant: GroupCount? get() = groupCounts.firstOrNull()
    val facesInDominant: Int get() = dominant?.count ?: 0
    val facesElsewhere: Int get() = faces - facesInDominant
    val isSplit: Boolean get() = groupCounts.size > 1
    val isPerfect: Boolean get() = groupCounts.size == 1
}

data class Collision(
    val photoName: String,
    val photoIndex: Int,
    val categoryId: String,
    val group: Int,
    val detectionIndices: List<Int>
)

data class ComboFailure(
    val photoName: String,
    val photoIndex: Int,
    val categoryId: String,
    val expected: List<Int>,
    val observed: List<String>,
    val reason: String
)

data class ThresholdRun(
    val threshold: Float,
    val faceCount: Int,
    val groupCount: Int,
    val groupOf: IntArray,
    val groups: List<GroupInfo>,
    val people: List<PersonOutcome>,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val trueNegatives: Int,
    val samePairs: Int,
    val differentPairs: Int,
    val precision: Float,
    val recall: Float,
    val f1: Float,
    val falsePositiveRate: Float,
    val mergedGroups: List<GroupInfo>,
    val splitPeople: List<PersonOutcome>,
    val perfectPeople: List<PersonOutcome>,
    val collisions: List<Collision>,
    val comboPhotosChecked: Int,
    val comboPhotosPassed: Int,
    val comboFailures: List<ComboFailure>,
    val mixedFacesInWrongGroup: Int,
    val unknownOnlyGroups: Int,
    val facesWithNoTruth: Int
)

// =============================================================================
// 4. CLUSTERING + EVALUATION ENGINE
// =============================================================================

private const val TRUTH_UNKNOWN: Byte = 0
private const val TRUTH_SAME: Byte = 1
private const val TRUTH_DIFFERENT: Byte = 2

class FaceLabEngine(
    private val faces: List<LabFace>,
    private val photos: List<LabPhoto>
) {

    val faceCount: Int get() = faces.size

    private val n = faces.size

    var embeddingAnomalies: Int = 0
        private set

    private val embeddedPerPhoto: IntArray by lazy {
        val counts = IntArray(photos.size)
        faces.forEach { face ->
            if (face.photoIndex in counts.indices) counts[face.photoIndex] = counts[face.photoIndex] + 1
        }
        counts
    }

    private val similarity: FloatArray by lazy { buildSimilarity() }

    private val pairTruth: ByteArray by lazy { buildTruth() }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            embeddingAnomalies++
            return 0f
        }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (!x.isFinite() || !y.isFinite()) {
                embeddingAnomalies++
                return 0f
            }
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    private fun buildSimilarity(): FloatArray {
        val matrix = FloatArray(n * n)
        for (i in 0 until n) {
            matrix[i * n + i] = 1f
            val a = faces[i].embedding
            for (j in i + 1 until n) {
                val value = cosine(a, faces[j].embedding)
                matrix[i * n + j] = value
                matrix[j * n + i] = value
            }
        }
        return matrix
    }

    private fun buildTruth(): ByteArray {
        val truth = ByteArray(n * n)
        for (i in 0 until n) {
            val a = faces[i]
            for (j in i + 1 until n) {
                val code = classify(a, faces[j])
                truth[i * n + j] = code
                truth[j * n + i] = code
            }
        }
        return truth
    }

    private fun classify(a: LabFace, b: LabFace): Byte {
        if (a.photoIndex == b.photoIndex) {
            val category = LAB_CATEGORY_MAP[a.categoryId]
            val samePhotoFaces = if (a.photoIndex in embeddedPerPhoto.indices) {
                embeddedPerPhoto[a.photoIndex]
            } else {
                0
            }
            if (category != null &&
                category.kind == LabCategoryKind.MIXED &&
                samePhotoFaces == category.people.size
            ) {
                return TRUTH_DIFFERENT
            }
        }

        val knownA = a.knownPerson
        val knownB = b.knownPerson

        return when {
            knownA != null && knownB != null ->
                if (knownA == knownB) TRUTH_SAME else TRUTH_DIFFERENT

            knownA != null && knownB == null ->
                if (knownA in b.allowedPeople) TRUTH_UNKNOWN else TRUTH_DIFFERENT

            knownA == null && knownB != null ->
                if (knownB in a.allowedPeople) TRUTH_UNKNOWN else TRUTH_DIFFERENT

            else ->
                if (a.allowedPeople.any { it in b.allowedPeople }) TRUTH_UNKNOWN else TRUTH_DIFFERENT
        }
    }

    private class Clustered(val groupOf: IntArray, val groups: List<List<Int>>)

    /** The greedy loop from FaceClusterer, minus the database. */
    private fun cluster(threshold: Float): Clustered {
        val groupOf = IntArray(n) { -1 }
        val groups = ArrayList<MutableList<Int>>()

        for (i in 0 until n) {
            var bestGroup = -1
            var highestSimilarity = -1f

            for (g in groups.indices) {
                val members = groups[g]
                for (member in members) {
                    val value = similarity[i * n + member]
                    if (value > highestSimilarity) {
                        highestSimilarity = value
                        if (value >= threshold) bestGroup = g
                    }
                }
            }

            if (bestGroup >= 0) {
                groups[bestGroup].add(i)
                groupOf[i] = bestGroup
            } else {
                groups.add(mutableListOf(i))
                groupOf[i] = groups.size - 1
            }
        }

        return Clustered(groupOf, groups.map { it.toList() })
    }

    fun evaluate(threshold: Float): ThresholdRun {
        if (n == 0) return emptyRun(threshold)

        val clustered = cluster(threshold)
        val groupOf = clustered.groupOf

        val groupInfos = clustered.groups.mapIndexed { groupIndex, members ->
            val counts = LinkedHashMap<Int, Int>()
            var unknown = 0
            for (member in members) {
                val person = faces[member].knownPerson
                if (person == null) unknown++ else counts[person] = (counts[person] ?: 0) + 1
            }
            val sorted = counts.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .map { PersonCount(it.key, it.value) }

            GroupInfo(
                index = groupIndex,
                faceCount = members.size,
                knownCounts = sorted,
                unknownCount = unknown,
                inferredPerson = sorted.firstOrNull()?.person,
                ambiguous = sorted.size > 1 && sorted[0].count == sorted[1].count
            )
        }

        val perPerson = HashMap<Int, HashMap<Int, Int>>()
        for (i in 0 until n) {
            val person = faces[i].knownPerson ?: continue
            val groups = perPerson.getOrPut(person) { HashMap() }
            val g = groupOf[i]
            groups[g] = (groups[g] ?: 0) + 1
        }

        val people = LAB_PEOPLE.map { person ->
            val groups = perPerson[person] ?: emptyMap()
            val counts = groups.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .map { GroupCount(it.key, it.value) }
            PersonOutcome(person = person, faces = counts.sumOf { it.count }, groupCounts = counts)
        }.filter { it.faces > 0 }

        var truePositives = 0
        var falsePositives = 0
        var falseNegatives = 0
        var trueNegatives = 0
        var samePairs = 0
        var differentPairs = 0

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val truth = pairTruth[i * n + j]
                if (truth == TRUTH_UNKNOWN) continue
                val sameGroup = groupOf[i] == groupOf[j]
                if (truth == TRUTH_SAME) {
                    samePairs++
                    if (sameGroup) truePositives++ else falseNegatives++
                } else {
                    differentPairs++
                    if (sameGroup) falsePositives++ else trueNegatives++
                }
            }
        }

        val precision = if (truePositives + falsePositives > 0) {
            truePositives.toFloat() / (truePositives + falsePositives).toFloat()
        } else {
            Float.NaN
        }
        val recall = if (truePositives + falseNegatives > 0) {
            truePositives.toFloat() / (truePositives + falseNegatives).toFloat()
        } else {
            Float.NaN
        }
        val f1 = if (precision.isFinite() && recall.isFinite() && precision + recall > 0f) {
            2f * precision * recall / (precision + recall)
        } else {
            Float.NaN
        }
        val falsePositiveRate = if (falsePositives + trueNegatives > 0) {
            falsePositives.toFloat() / (falsePositives + trueNegatives).toFloat()
        } else {
            Float.NaN
        }

        val collisions = ArrayList<Collision>()
        val facesByPhoto = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) facesByPhoto.getOrPut(faces[i].photoIndex) { mutableListOf() }.add(i)

        for ((photoIndex, indices) in facesByPhoto) {
            if (indices.size < 2) continue
            val byGroup = indices.groupBy { groupOf[it] }
            for ((group, members) in byGroup) {
                if (members.size >= 2) {
                    collisions.add(
                        Collision(
                            photoName = faces[members.first()].photoName,
                            photoIndex = photoIndex,
                            categoryId = faces[members.first()].categoryId,
                            group = group,
                            detectionIndices = members.map { faces[it].detectionIndex }.sorted()
                        )
                    )
                }
            }
        }

        val comboFailures = ArrayList<ComboFailure>()
        var comboChecked = 0
        var comboPassed = 0
        var mixedFacesInWrongGroup = 0

        for (category in LAB_CATEGORIES.filter { it.kind == LabCategoryKind.MIXED }) {
            val photoIndices = faces.filter { it.categoryId == category.id }
                .map { it.photoIndex }
                .distinct()

            for (photoIndex in photoIndices) {
                val photoFaces = (facesByPhoto[photoIndex] ?: continue)
                    .sortedBy { faces[it].detectionIndex }
                if (photoFaces.isEmpty()) continue

                comboChecked++

                val observed = photoFaces.map { faceIndex ->
                    val group = groupOf[faceIndex]
                    val inferred = groupInfos[group].inferredPerson
                    if (inferred == null) "unidentified (G$group)" else "P$inferred (G$group)"
                }

                val inferredPersons = photoFaces.map { groupInfos[groupOf[it]].inferredPerson }
                val groupsUsed = photoFaces.map { groupOf[it] }.distinct()

                var reason: String? = null
                if (groupsUsed.size < photoFaces.size) {
                    reason = "Two faces from this photo were put in the same group."
                } else if (inferredPersons.any { it != null && it !in category.people }) {
                    reason = "A face was matched to someone who is not in this photo."
                } else if (photoFaces.size == category.people.size &&
                    inferredPersons.filterNotNull().toSet() != category.people.toSet()
                ) {
                    reason = "The people found are not the people listed for this photo."
                }

                mixedFacesInWrongGroup += inferredPersons.count { it != null && it !in category.people }

                if (reason == null) {
                    comboPassed++
                } else {
                    comboFailures.add(
                        ComboFailure(
                            photoName = faces[photoFaces.first()].photoName,
                            photoIndex = photoIndex,
                            categoryId = category.id,
                            expected = category.people,
                            observed = observed,
                            reason = reason
                        )
                    )
                }
            }
        }

        return ThresholdRun(
            threshold = threshold,
            faceCount = n,
            groupCount = groupInfos.size,
            groupOf = groupOf,
            groups = groupInfos,
            people = people,
            truePositives = truePositives,
            falsePositives = falsePositives,
            falseNegatives = falseNegatives,
            trueNegatives = trueNegatives,
            samePairs = samePairs,
            differentPairs = differentPairs,
            precision = precision,
            recall = recall,
            f1 = f1,
            falsePositiveRate = falsePositiveRate,
            mergedGroups = groupInfos.filter { it.isMerged },
            splitPeople = people.filter { it.isSplit },
            perfectPeople = people.filter { it.isPerfect },
            collisions = collisions,
            comboPhotosChecked = comboChecked,
            comboPhotosPassed = comboPassed,
            comboFailures = comboFailures,
            mixedFacesInWrongGroup = mixedFacesInWrongGroup,
            unknownOnlyGroups = groupInfos.count { it.knownCounts.isEmpty() },
            facesWithNoTruth = faces.count { it.knownPerson == null }
        )
    }

    private fun emptyRun(threshold: Float): ThresholdRun = ThresholdRun(
        threshold = threshold,
        faceCount = 0,
        groupCount = 0,
        groupOf = IntArray(0),
        groups = emptyList(),
        people = emptyList(),
        truePositives = 0,
        falsePositives = 0,
        falseNegatives = 0,
        trueNegatives = 0,
        samePairs = 0,
        differentPairs = 0,
        precision = Float.NaN,
        recall = Float.NaN,
        f1 = Float.NaN,
        falsePositiveRate = Float.NaN,
        mergedGroups = emptyList(),
        splitPeople = emptyList(),
        perfectPeople = emptyList(),
        collisions = emptyList(),
        comboPhotosChecked = 0,
        comboPhotosPassed = 0,
        comboFailures = emptyList(),
        mixedFacesInWrongGroup = 0,
        unknownOnlyGroups = 0,
        facesWithNoTruth = 0
    )
}

// =============================================================================
// 5. VIEW MODEL
// =============================================================================

private fun signatureOf(photos: List<LabPhoto>): String {
    var hash = 17L
    photos.forEach { photo ->
        hash = hash * 31L + photo.id.hashCode().toLong()
        hash = hash * 31L + photo.categoryId.hashCode().toLong()
    }
    return "${photos.size}_$hash"
}

/** Survives back-navigation inside one process. */
private object FaceLabCache {
    var signature: String? = null
    var engine: FaceLabEngine? = null
    var reports: List<LabPhotoReport> = emptyList()
    var presets: List<ThresholdRun> = emptyList()
    var faces: List<LabFace> = emptyList()

    fun clear() {
        signature = null
        engine = null
        reports = emptyList()
        presets = emptyList()
        faces = emptyList()
    }
}

@HiltViewModel
class FaceSortingLabViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val faceDetector: MlKitFaceDetector,
    private val faceEmbedder: FaceEmbedder
) : ViewModel() {

    private val store = LabDatasetStore(context)

    private val cropsDir: File by lazy {
        File(context.filesDir, CROP_DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    var photos by mutableStateOf<List<LabPhoto>>(emptyList())
        private set

    var isRunning by mutableStateOf(false)
        private set

    var status by mutableStateOf("Pick a category, then add photos to it.")
        private set

    var progress by mutableStateOf(0f)
        private set

    var progressLabel by mutableStateOf("")
        private set

    var photoReports by mutableStateOf<List<LabPhotoReport>>(emptyList())
        private set

    var analysedFaces by mutableStateOf<List<LabFace>>(emptyList())
        private set

    var presetRuns by mutableStateOf<List<ThresholdRun>>(emptyList())
        private set

    var focusedThreshold by mutableStateOf(0.35f)
        private set

    var focusedRun by mutableStateOf<ThresholdRun?>(null)
        private set

    var hasResults by mutableStateOf(false)
        private set

    var restoreNote by mutableStateOf<String?>(null)
        private set

    private var engine: FaceLabEngine? = null
    private var analysisJob: Job? = null
    private var focusJob: Job? = null
    private var saveJob: Job? = null

    init {
        val restored = store.loadPhotos()
        photos = restored
        focusedThreshold = store.loadThreshold().coerceIn(LAB_MIN_THRESHOLD, LAB_MAX_THRESHOLD)

        if (restored.isEmpty()) {
            status = "No dataset yet. Add photos to any category to begin."
        } else {
            status = "${restored.size} photos ready."
            verifyRestoredPhotos()
        }

        if (restored.isNotEmpty() && signatureOf(restored) == FaceLabCache.signature) {
            val cached = FaceLabCache.engine
            if (cached != null) {
                engine = cached
                photoReports = FaceLabCache.reports
                analysedFaces = FaceLabCache.faces
                presetRuns = FaceLabCache.presets
                hasResults = true
                status = "Last run restored — ${cached.faceCount} faces."
                refreshFocusedRun()
            }
        }
    }

    // ------------------------------------------------------------- dataset ops

    fun countFor(categoryId: String): Int = photos.count { it.categoryId == categoryId }

    fun addPhotos(categoryId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val category = LAB_CATEGORY_MAP[categoryId] ?: return

        val existing = photos.map { it.uri.toString() }.toHashSet()
        val additions = ArrayList<LabPhoto>()
        var duplicates = 0

        for (raw in uris) {
            val uri = toPersistableUri(raw)
            val key = uri.toString()
            if (key in existing) {
                duplicates++
                continue
            }
            existing.add(key)
            try {
                context.contentResolver.takePersistableUriPermission(
                    raw, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
                // Picker grants are not always persistable.
            }
            additions.add(
                LabPhoto(
                    id = key,
                    uri = uri,
                    categoryId = categoryId,
                    displayName = resolveDisplayName(uri)
                )
            )
        }

        if (additions.isEmpty()) {
            status = "Those photos are already in the dataset."
            return
        }

        photos = photos + additions
        invalidateResults()
        persist()

        status = buildString {
            append("Added ${additions.size} to ${category.title}.")
            if (duplicates > 0) append(" Skipped $duplicates duplicate(s).")
        }
    }

    fun removePhoto(photoId: String) {
        val photo = photos.find { it.id == photoId } ?: return
        photos = photos.filterNot { it.id == photoId }
        releasePermission(photo)
        invalidateResults()
        persist()
        status = "Removed ${photo.displayName}."
    }

    fun clearCategory(categoryId: String) {
        val removed = photos.filter { it.categoryId == categoryId }
        if (removed.isEmpty()) return
        photos = photos.filterNot { it.categoryId == categoryId }
        removed.forEach { releasePermission(it) }
        invalidateResults()
        persist()
        status = "Cleared ${removed.size} photo(s)."
    }

    fun clearAll() {
        analysisJob?.cancel()
        photos.forEach { releasePermission(it) }
        photos = emptyList()
        invalidateResults()
        FaceLabCache.clear()
        store.clear()
        deleteCrops()
        restoreNote = null
        status = "Dataset cleared."
    }

    private fun releasePermission(photo: LabPhoto) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                photo.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------- thresholds

    /**
     * Not named `setFocusedThreshold`: the `focusedThreshold` property already
     * generates that setter, and the JVM signatures would clash.
     */
    fun selectThreshold(value: Float) {
        val clamped = value.coerceIn(LAB_MIN_THRESHOLD, LAB_MAX_THRESHOLD)
        focusedThreshold = clamped
        store.saveThreshold(clamped)
        refreshFocusedRun()
    }

    private fun refreshFocusedRun() {
        val currentEngine = engine
        if (currentEngine == null) {
            focusedRun = null
            return
        }
        val threshold = focusedThreshold
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            val run = withContext(Dispatchers.Default) { currentEngine.evaluate(threshold) }
            focusedRun = run
        }
    }

    // -------------------------------------------------------------- analysis

    fun runAnalysis() {
        if (isRunning) return
        if (photos.isEmpty()) {
            status = "Add photos to at least one category first."
            return
        }

        val snapshot = photos.toList()

        analysisJob = viewModelScope.launch {
            isRunning = true
            progress = 0f
            photoReports = emptyList()
            analysedFaces = emptyList()
            presetRuns = emptyList()
            focusedRun = null
            hasResults = false
            status = "Starting…"

            try {
                deleteCrops()

                val reports = ArrayList<LabPhotoReport>(snapshot.size)
                val faces = ArrayList<LabFace>()

                for ((index, photo) in snapshot.withIndex()) {
                    progressLabel = "${index + 1} / ${snapshot.size} · ${photo.displayName}"
                    progress = index.toFloat() / snapshot.size.toFloat()
                    status = "Analysing $progressLabel"

                    val processed = withContext(Dispatchers.Default) {
                        processPhoto(photo, reports.size)
                    }

                    reports.add(processed.report)
                    faces.addAll(processed.faces)
                }

                progress = 1f
                status = "Scoring ${faces.size} faces across ${LAB_PRESET_THRESHOLDS.size} thresholds…"

                val indexed = faces.mapIndexed { index, face -> face.copy(engineIndex = index) }
                val newEngine = FaceLabEngine(indexed, snapshot)
                val runs = withContext(Dispatchers.Default) {
                    LAB_PRESET_THRESHOLDS.map { newEngine.evaluate(it) }
                }

                engine = newEngine
                photoReports = reports.toList()
                analysedFaces = indexed
                presetRuns = runs
                hasResults = true

                FaceLabCache.signature = signatureOf(snapshot)
                FaceLabCache.engine = newEngine
                FaceLabCache.reports = reports.toList()
                FaceLabCache.presets = runs
                FaceLabCache.faces = indexed

                val best = runs.filter { it.f1.isFinite() && it.faceCount > 0 }.maxByOrNull { it.f1 }
                if (best != null) {
                    focusedThreshold = best.threshold
                    store.saveThreshold(best.threshold)
                }
                refreshFocusedRun()

                status = if (faces.isEmpty()) {
                    "No usable faces were found. Open the photos tab to see why."
                } else {
                    buildString {
                        append("${faces.size} faces from ${snapshot.size} photos.")
                        if (best != null) append(" Best F1 at ${formatThreshold(best.threshold)}.")
                    }
                }
            } catch (e: CancellationException) {
                status = "Cancelled."
                throw e
            } catch (e: Exception) {
                status = "Failed: ${e.message ?: e::class.simpleName}"
            } finally {
                isRunning = false
                progress = 0f
                progressLabel = ""
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
    }

    private class ProcessedPhoto(val faces: List<LabFace>, val report: LabPhotoReport)

    private suspend fun processPhoto(photo: LabPhoto, reportIndex: Int): ProcessedPhoto {
        val category = LAB_CATEGORY_MAP[photo.categoryId]
            ?: return ProcessedPhoto(
                emptyList(),
                LabPhotoReport(
                    photoId = photo.id,
                    displayName = photo.displayName,
                    categoryId = photo.categoryId,
                    rawDetections = 0,
                    acceptedDetections = 0,
                    embeddedFaces = 0,
                    rejected = emptyList(),
                    imageWidth = 0,
                    imageHeight = 0,
                    error = "Unknown category."
                )
            )

        var decoded: Bitmap? = null
        try {
            val loaded = BitmapLoader.loadBitmap(
                contentResolver = context.contentResolver,
                uriString = photo.uri.toString()
            )

            if (loaded == null) {
                return ProcessedPhoto(
                    emptyList(),
                    LabPhotoReport(
                        photoId = photo.id,
                        displayName = photo.displayName,
                        categoryId = photo.categoryId,
                        rawDetections = 0,
                        acceptedDetections = 0,
                        embeddedFaces = 0,
                        rejected = emptyList(),
                        imageWidth = 0,
                        imageHeight = 0,
                        error = "Could not open this photo. Remove it and add it again."
                    )
                )
            }

            decoded = loaded
            val source: Bitmap = loaded

            coroutineContext.ensureActive()

            val diagnostics = faceDetector.detectFacesWithDiagnostics(source)
            coroutineContext.ensureActive()

            val rejected = ArrayList<RejectedFace>()
            var acceptedCount = 0
            val perPhotoFaces = ArrayList<LabFace>()

            diagnostics.forEachIndexed { detectionIndex, diagnostic ->
                val box = diagnostic.face.boundingBox
                if (!diagnostic.accepted) {
                    rejected.add(
                        RejectedFace(
                            detectionIndex = detectionIndex + 1,
                            reasons = diagnostic.rejectionReasons,
                            boxWidth = box.width(),
                            boxHeight = box.height()
                        )
                    )
                    return@forEachIndexed
                }

                acceptedCount++

                val cropped = try {
                    FaceCropper.cropFace(source, diagnostic.face, margin = 0.2f)
                } catch (t: Throwable) {
                    null
                }

                if (cropped == null) return@forEachIndexed

                var preprocessed: Bitmap? = null
                var embedding: FloatArray? = null
                try {
                    val pre = FaceCropper.preprocessForEmbedding(cropped)
                    preprocessed = pre
                    embedding = faceEmbedder.generateEmbedding(pre)
                } catch (t: Throwable) {
                    embedding = null
                } finally {
                    if (preprocessed != null && preprocessed !== cropped) preprocessed.recycle()
                    cropped.recycle()
                }

                val values = embedding
                if (values == null || values.isEmpty() || values.any { !it.isFinite() }) {
                    return@forEachIndexed
                }

                val cropPath = writeCrop(preprocessed, reportIndex, detectionIndex + 1)

                perPhotoFaces.add(
                    LabFace(
                        photoIndex = reportIndex,
                        photoName = photo.displayName,
                        categoryId = photo.categoryId,
                        detectionIndex = detectionIndex + 1,
                        embedding = values,
                        boxLeft = box.left,
                        boxTop = box.top,
                        boxRight = box.right,
                        boxBottom = box.bottom,
                        imageWidth = source.width,
                        imageHeight = source.height,
                        cropPath = cropPath,
                        knownPerson = null,
                        allowedPeople = category.people.toSet()
                    )
                )
            }

            val singlePerson = if (category.kind == LabCategoryKind.SINGLE) category.people.first() else null
            val certainIdentity = singlePerson != null && acceptedCount == 1 && perPhotoFaces.size == 1

            val faces = perPhotoFaces.map { face ->
                if (certainIdentity) face.copy(knownPerson = singlePerson) else face
            }

            return ProcessedPhoto(
                faces = faces,
                report = LabPhotoReport(
                    photoId = photo.id,
                    displayName = photo.displayName,
                    categoryId = photo.categoryId,
                    rawDetections = diagnostics.size,
                    acceptedDetections = acceptedCount,
                    embeddedFaces = perPhotoFaces.size,
                    rejected = rejected,
                    imageWidth = source.width,
                    imageHeight = source.height,
                    error = null,
                    note = buildNote(category, acceptedCount, perPhotoFaces.size)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProcessedPhoto(
                emptyList(),
                LabPhotoReport(
                    photoId = photo.id,
                    displayName = photo.displayName,
                    categoryId = photo.categoryId,
                    rawDetections = 0,
                    acceptedDetections = 0,
                    embeddedFaces = 0,
                    rejected = emptyList(),
                    imageWidth = 0,
                    imageHeight = 0,
                    error = "${e::class.simpleName}: ${e.message ?: "unknown"}"
                )
            )
        } finally {
            decoded?.recycle()
        }
    }

    private fun buildNote(category: LabCategory, acceptedCount: Int, embeddedCount: Int): String? = when {
        acceptedCount == 0 -> "No face survived detection."
        category.kind == LabCategoryKind.SINGLE && acceptedCount > 1 ->
            "$acceptedCount faces in a single-person photo — excluded from scoring."
        embeddedCount == 0 -> "Detected, but could not be cropped or embedded."
        category.kind == LabCategoryKind.MIXED && embeddedCount < category.people.size ->
            "$embeddedCount of ${category.people.size} people gave a usable face."
        category.kind == LabCategoryKind.MIXED && embeddedCount > category.people.size ->
            "$embeddedCount faces for ${category.people.size} people."
        else -> null
    }

    /** Writes the exact 112x112 bitmap the embedder saw. */
    private fun writeCrop(source: Bitmap?, photoIndex: Int, detectionIndex: Int): String? {
        if (source == null) return null
        return try {
            val file = File(cropsDir, "${photoIndex}_$detectionIndex.jpg")
            FileOutputStream(file).use { out ->
                source.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    private fun deleteCrops() {
        try {
            cropsDir.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ maintenance

    private fun invalidateResults() {
        analysisJob?.cancel()
        engine = null
        photoReports = emptyList()
        analysedFaces = emptyList()
        presetRuns = emptyList()
        focusedRun = null
        hasResults = false
        FaceLabCache.clear()
    }

    private fun persist() {
        val snapshot = photos
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) { store.savePhotos(snapshot) }
    }

    private fun verifyRestoredPhotos() {
        val snapshot = photos
        viewModelScope.launch(Dispatchers.IO) {
            val readable = ArrayList<LabPhoto>(snapshot.size)
            var dropped = 0
            snapshot.forEach { photo ->
                val ok = try {
                    context.contentResolver.openInputStream(photo.uri)?.use { true } ?: false
                } catch (t: Throwable) {
                    false
                }
                if (ok) readable.add(photo) else dropped++
            }
            if (dropped > 0) {
                withContext(Dispatchers.Main) {
                    photos = readable
                    restoreNote = "$dropped photo(s) could not be re-opened and were dropped."
                    persist()
                }
            }
        }
    }

    private fun toPersistableUri(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return uri
        return try {
            MediaStore.getMediaUri(context, uri) ?: uri
        } catch (t: Throwable) {
            uri
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            } ?: uri.lastPathSegment ?: "Photo"
        } catch (t: Throwable) {
            uri.lastPathSegment ?: "Photo"
        }
    }

    // ---------------------------------------------------------- text report

    fun buildTextReport(): String {
        val runs = presetRuns
        val builder = StringBuilder()

        builder.appendLine("CHRONICLE — FACE SORTING LAB")
        builder.appendLine("Photos: ${photos.size} · faces: ${analysedFaces.size}")
        builder.appendLine()

        if (runs.isEmpty()) {
            builder.appendLine("No analysis has been run yet.")
            return builder.toString()
        }

        builder.appendLine("THRESHOLD SWEEP")
        runs.forEach { run ->
            builder.appendLine(
                String.format(
                    Locale.US, "%.2f  groups %d  prec %s  rec %s  f1 %s  merged %d  split %d  collide %d",
                    run.threshold,
                    run.groupCount,
                    formatRatio(run.precision),
                    formatRatio(run.recall),
                    formatRatio(run.f1),
                    run.mergedGroups.size,
                    run.splitPeople.size,
                    run.collisions.size
                )
            )
        }
        builder.appendLine()

        val detail = focusedRun ?: runs.first()
        appendRunDetail(builder, detail)
        return builder.toString()
    }

    private fun appendRunDetail(builder: StringBuilder, run: ThresholdRun) {
        builder.appendLine("DETAIL AT ${formatThreshold(run.threshold)}")
        builder.appendLine("Groups: ${run.groupCount} for ${run.people.size} labelled people")
        builder.appendLine()

        builder.appendLine("PER PERSON")
        run.people.forEach { outcome ->
            builder.appendLine(
                "  Person ${outcome.person}: ${outcome.faces} faces → " +
                        outcome.groupCounts.joinToString(", ") { "G${it.group} ×${it.count}" }
            )
        }
        builder.appendLine()

        if (run.mergedGroups.isNotEmpty()) {
            builder.appendLine("MERGED GROUPS")
            run.mergedGroups.forEach {
                builder.appendLine("  G${it.index}: ${it.compositionLabel} (${it.faceCount} faces)")
            }
            builder.appendLine()
        }

        if (run.splitPeople.isNotEmpty()) {
            builder.appendLine("SPLIT PEOPLE")
            run.splitPeople.forEach {
                builder.appendLine(
                    "  Person ${it.person}: " +
                            it.groupCounts.joinToString(", ") { g -> "G${g.group} ×${g.count}" }
                )
            }
            builder.appendLine()
        }

        if (run.collisions.isNotEmpty()) {
            builder.appendLine("SAME-PHOTO COLLISIONS")
            run.collisions.forEach {
                builder.appendLine("  ${it.photoName}: detections ${it.detectionIndices.joinToString(", ")} in G${it.group}")
            }
            builder.appendLine()
        }

        builder.appendLine("COMBINATION PHOTOS: ${run.comboPhotosPassed}/${run.comboPhotosChecked}")
        run.comboFailures.forEach {
            builder.appendLine("  ${it.photoName}: expected ${it.expected.joinToString("+") { p -> "P$p" }} — ${it.reason}")
        }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        focusJob?.cancel()
        saveJob?.cancel()
        super.onCleared()
    }
}

// =============================================================================
// 6. FORMATTING
// =============================================================================

private fun formatThreshold(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun formatRatio(value: Float): String =
    if (value.isFinite()) String.format(Locale.US, "%.2f", value) else "—"

private fun formatPercent(value: Float): String =
    if (value.isFinite()) String.format(Locale.US, "%.0f%%", value * 100f) else "—"

// =============================================================================
// 7. VISUAL PRIMITIVES
// =============================================================================

private val TileShape = RoundedCornerShape(12.dp)
private val GoodColor = Color(0xFF4CAF7D)
private val BadColor = Color(0xFFE2564A)
private val WarnColor = Color(0xFFE9A23B)

private fun groupTag(group: Int): String = "G$group"

/** A face crop from disk, or a placeholder if the crop could not be written. */
@Composable
private fun FaceChip(
    face: LabFace,
    group: Int,
    accent: Color,
    size: Int = 64,
    label: String? = null,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 2.dp, color = accent, shape = shape)
            .then(onClick?.let { callback -> Modifier.clickable { callback() } } ?: Modifier)
    ) {
        val path = face.cropPath
        if (path != null && File(path).exists()) {
            AsyncImage(
                model = File(path),
                contentDescription = "Face ${face.detectionIndex}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(2.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        ) {
            Text(
                text = label ?: groupTag(group),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** A photo with its detected face boxes drawn on top. */
@Composable
private fun PhotoWithBoxes(
    model: Any,
    imageWidth: Int,
    imageHeight: Int,
    faces: List<LabFace>,
    faceColor: (LabFace) -> Color,
    modifier: Modifier = Modifier
) {
    val ratio = if (imageWidth > 0 && imageHeight > 0) {
        imageWidth.toFloat() / imageHeight.toFloat()
    } else {
        1f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(if (ratio > 0f) ratio else 1f)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val boxWidth = maxWidth
        val boxHeight = maxHeight

        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        val strokePx = with(LocalDensity.current) { 2.dp.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
            val canvasWidth = size.width
            val canvasHeight = size.height

            faces.forEach { face ->
                val left = face.leftFraction * canvasWidth
                val top = face.topFraction * canvasHeight
                val right = face.rightFraction * canvasWidth
                val bottom = face.bottomFraction * canvasHeight

                drawRect(
                    color = faceColor(face),
                    topLeft = Offset(left, top),
                    size = Size(
                        (right - left).coerceAtLeast(1f),
                        (bottom - top).coerceAtLeast(1f)
                    ),
                    style = Stroke(width = strokePx)
                )
            }
        }

        faces.forEach { face ->
            val x = boxWidth * face.leftFraction
            val y = boxHeight * face.topFraction
            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .clip(RoundedCornerShape(4.dp))
                    .background(faceColor(face).copy(alpha = 0.92f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = face.detectionIndex.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, filled: Boolean = true) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(accent.copy(alpha = 0.10f))
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = accent,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// =============================================================================
// 8. SCREEN
// =============================================================================

private enum class LabView { PEOPLE, PHOTOS, MISTAKES }

@Composable
fun FaceSortingLabScreen(
    viewModel: FaceSortingLabViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var pendingCategoryId by remember { mutableStateOf<String?>(null) }
    var view by rememberSaveable { mutableStateOf(LabView.PEOPLE.name) }
    var showSetup by rememberSaveable { mutableStateOf(true) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    var expandedPhoto by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        val target = pendingCategoryId
        pendingCategoryId = null
        if (target != null && uris.isNotEmpty()) viewModel.addPhotos(target, uris)
    }

    val photosByCategory = remember(viewModel.photos) { viewModel.photos.groupBy { it.categoryId } }
    val run = viewModel.focusedRun

    val facesByPhoto = remember(viewModel.analysedFaces) {
        viewModel.analysedFaces.withIndex().groupBy { it.value.photoIndex }
    }

    val currentView = runCatching { LabView.valueOf(view) }.getOrDefault(LabView.PEOPLE)

    // Computed outside the LazyColumn: LazyListScope is not a composable scope.
    val peopleFaces = remember(run, viewModel.analysedFaces) {
        viewModel.analysedFaces.withIndex()
            .filter { it.value.knownPerson != null }
            .groupBy { it.value.knownPerson ?: -1 }
    }

    val photoRows = remember(viewModel.photoReports) {
        viewModel.photoReports.indices.toList().chunked(2)
    }

    val mistakes = remember(run, viewModel.analysedFaces) {
        if (run == null) emptyList() else buildMistakes(run)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        // ------------------------------------------------------------- header
        item(key = "header") {
            LabHeader(
                photoCount = viewModel.photos.size,
                filledCategories = LAB_CATEGORIES.count { (photosByCategory[it.id]?.size ?: 0) > 0 },
                faceCount = viewModel.analysedFaces.size,
                isRunning = viewModel.isRunning,
                hasResults = viewModel.hasResults,
                status = viewModel.status,
                progress = viewModel.progress,
                progressLabel = viewModel.progressLabel,
                restoreNote = viewModel.restoreNote,
                showSetup = showSetup,
                showRules = showRules,
                onToggleSetup = { showSetup = !showSetup },
                onToggleRules = { showRules = !showRules },
                onRun = viewModel::runAnalysis,
                onCancel = viewModel::cancelAnalysis,
                onClearAll = viewModel::clearAll
            )
        }

        if (showRules) {
            item(key = "rules") { RulesCard() }
        }

        // -------------------------------------------------------------- setup
        if (showSetup) {
            LAB_BLOCKS.forEach { block ->
                item(key = "block_${block.id}") {
                    Text(
                        text = block.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)
                    )
                }

                items(
                    items = LAB_CATEGORIES.filter { it.blockId == block.id },
                    key = { "cat_${it.id}" }
                ) { category ->
                    CategoryStrip(
                        category = category,
                        photos = photosByCategory[category.id] ?: emptyList(),
                        enabled = !viewModel.isRunning,
                        onAdd = {
                            pendingCategoryId = category.id
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = viewModel::removePhoto,
                        onClear = { viewModel.clearCategory(category.id) }
                    )
                }
            }
        }

        if (!viewModel.hasResults) {
            item(key = "footer_empty") { Spacer(Modifier.height(24.dp)) }
        }

        // ------------------------------------------------------------- report
        if (viewModel.hasResults && run != null) {
            item(key = "controls") {
                ThresholdControls(
                    runs = viewModel.presetRuns,
                    focused = viewModel.focusedThreshold,
                    onPick = viewModel::selectThreshold
                )
            }

            item(key = "scores") { ScoreStrip(run) }

            item(key = "views") {
                ViewSwitcher(
                    selected = currentView,
                    peopleCount = run.people.size,
                    photoCount = viewModel.photoReports.size,
                    mistakeCount = countMistakes(run),
                    onSelect = { view = it.name }
                )
            }

            when (currentView) {
                LabView.PEOPLE -> {
                    if (run.people.isEmpty()) {
                        item(key = "people_empty") {
                            EmptyNote(
                                "No single-person photo produced exactly one face, so no identity could be scored."
                            )
                        }
                    }

                    items(items = run.people, key = { "person_${it.person}" }) { outcome ->
                        PersonCard(
                            outcome = outcome,
                            run = run,
                            faces = peopleFaces[outcome.person] ?: emptyList()
                        )
                    }
                }

                LabView.PHOTOS -> {
                    items(items = photoRows, key = { "row_${it.firstOrNull() ?: 0}" }) { row ->
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { photoIndex ->
                                    val report = viewModel.photoReports.getOrNull(photoIndex)
                                        ?: return@forEach
                                    PhotoTile(
                                        report = report,
                                        faces = facesByPhoto[photoIndex]?.map { it.value } ?: emptyList(),
                                        run = run,
                                        selected = expandedPhoto == report.photoId,
                                        modifier = Modifier.weight(1f),
                                        onToggle = {
                                            expandedPhoto = if (expandedPhoto == report.photoId) {
                                                null
                                            } else {
                                                report.photoId
                                            }
                                        }
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }

                            val expandedIndex = expandedPhoto?.let { id ->
                                viewModel.photoReports.indexOfFirst { it.photoId == id }
                            } ?: -1

                            if (expandedIndex >= 0 && row.contains(expandedIndex)) {
                                PhotoDetail(
                                    report = viewModel.photoReports[expandedIndex],
                                    faces = facesByPhoto[expandedIndex]?.map { it.value } ?: emptyList(),
                                    run = run
                                )
                            }
                        }
                    }
                }

                LabView.MISTAKES -> {
                    if (mistakes.isEmpty()) {
                        item(key = "mistakes_empty") {
                            EmptyNote(
                                "Nothing went wrong at this threshold — every person sits in one clean group " +
                                        "and no two people share a group."
                            )
                        }
                    }

                    items(items = mistakes, key = { it.key }) { mistake ->
                        MistakeCard(
                            mistake = mistake,
                            run = run,
                            faces = viewModel.analysedFaces,
                            reports = viewModel.photoReports
                        )
                    }
                }
            }

            item(key = "copy") {
                OutlinedButton(
                    onClick = {
                        val text = viewModel.buildTextReport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Face sorting report", text))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("Copy text report")
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Header
// -----------------------------------------------------------------------------

@Composable
private fun LabHeader(
    photoCount: Int,
    filledCategories: Int,
    faceCount: Int,
    isRunning: Boolean,
    hasResults: Boolean,
    status: String,
    progress: Float,
    progressLabel: String,
    restoreNote: String?,
    showSetup: Boolean,
    showRules: Boolean,
    onToggleSetup: () -> Unit,
    onToggleRules: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Face Sorting Lab",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("$filledCategories / ${LAB_CATEGORIES.size} categories", MaterialTheme.colorScheme.primary)
            Pill("$photoCount photos", MaterialTheme.colorScheme.secondary)
            if (hasResults) Pill("$faceCount faces", GoodColor)
        }

        if (isRunning) {
            ProgressBar(fraction = progress)
            Text(
                text = progressLabel.ifBlank { status },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (restoreNote != null) {
            Text(
                text = restoreNote,
                style = MaterialTheme.typography.labelSmall,
                color = BadColor
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isRunning) {
                Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            } else {
                Button(
                    onClick = onRun,
                    enabled = photoCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (hasResults) "Re-run" else "Run analysis")
                }
            }

            OutlinedButton(onClick = onToggleSetup) {
                Text(if (showSetup) "Hide setup" else "Setup")
            }

            OutlinedButton(onClick = onClearAll, enabled = photoCount > 0 && !isRunning) {
                Text("Clear")
            }
        }

        TextButton(onClick = onToggleRules, contentPadding = PaddingValues(0.dp)) {
            Text(if (showRules) "Hide ground-truth rules" else "Ground-truth rules", fontSize = 12.sp)
        }
    }
}

@Composable
private fun RulesCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Rule("A single-person photo is scored only when exactly one face is found in it. Two faces → the photo is shown but not scored, so nothing is a guess.")
            Rule("A combination photo proves who is in the frame, never which face is which person. Those faces are checked for consistency, not scored on identity.")
            Rule("Two faces in one combination photo count as different people only when the photo produced exactly as many faces as it lists people.")
            Rule("Same pipeline as production: ML Kit accurate, the same validation filters, the same 5-point alignment, the same SFace embedding, the same greedy clustering loop as FaceClusterer.")
            Rule("Faces are inserted in upload order, and greedy clustering is order-sensitive — re-adding the same photos in a different order can change the grouping.")
        }
    }
}

@Composable
private fun Rule(text: String) {
    Row {
        Text("·  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// -----------------------------------------------------------------------------
// Category setup strip
// -----------------------------------------------------------------------------

@Composable
private fun CategoryStrip(
    category: LabCategory,
    photos: List<LabPhoto>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    val count = photos.size
    val complete = count >= LAB_TARGET_PHOTOS_PER_CATEGORY
    val accent = if (complete) GoodColor else MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = category.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = category.instruction,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$count/$LAB_TARGET_PHOTOS_PER_CATEGORY",
                fontWeight = FontWeight.Bold,
                color = accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        ProgressBar(
            fraction = count.toFloat() / LAB_TARGET_PHOTOS_PER_CATEGORY.toFloat(),
            color = accent
        )

        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(items = photos, key = { it.id }) { photo ->
                    ThumbWithRemove(photo = photo, enabled = enabled, onRemove = { onRemove(photo.id) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAdd, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (photos.isEmpty()) "Add photos" else "Add more", fontSize = 12.sp)
            }
            if (photos.isNotEmpty()) {
                TextButton(onClick = onClear, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Clear", fontSize = 12.sp, color = BadColor)
                }
            }
        }
    }
}

@Composable
private fun ThumbWithRemove(photo: LabPhoto, enabled: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = photo.id) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, photo.uri, 200) }
    }

    Box(Modifier.size(56.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            val current = image
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(enabled = enabled) { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun decodeThumbnail(context: Context, uri: Uri, target: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        bitmap.asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}

// -----------------------------------------------------------------------------
// Threshold controls
// -----------------------------------------------------------------------------

@Composable
private fun ThresholdControls(
    runs: List<ThresholdRun>,
    focused: Float,
    onPick: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Threshold", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatThreshold(focused),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            val best = runs.filter { it.f1.isFinite() }.maxByOrNull { it.f1 }
            if (best != null) Pill("best F1 ${formatRatio(best.f1)}", GoodColor)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            runs.forEach { run ->
                val selected = abs(run.threshold - focused) < 0.001f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onPick(run.threshold) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatThreshold(run.threshold),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Slider(
            value = focused,
            onValueChange = onPick,
            valueRange = LAB_MIN_THRESHOLD..LAB_MAX_THRESHOLD,
            steps = 24
        )
    }
}

// -----------------------------------------------------------------------------
// Score strip
// -----------------------------------------------------------------------------

@Composable
private fun ScoreStrip(run: ThresholdRun) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("precision", formatRatio(run.precision), GoodColor, Modifier.weight(1f))
            MetricTile("recall", formatRatio(run.recall), GoodColor, Modifier.weight(1f))
            MetricTile("f1", formatRatio(run.f1), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            MetricTile("groups", "${run.groupCount}", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (run.mergedGroups.isNotEmpty()) Pill("${run.mergedGroups.size} merged", BadColor)
            if (run.splitPeople.isNotEmpty()) Pill("${run.splitPeople.size} split", WarnColor)
            if (run.collisions.isNotEmpty()) Pill("${run.collisions.size} collisions", BadColor)
            if (run.comboFailures.isNotEmpty()) Pill("${run.comboFailures.size} combo errors", BadColor)
            if (run.mergedGroups.isEmpty() && run.splitPeople.isEmpty() && run.collisions.isEmpty()) {
                Pill("clean run", GoodColor)
            }
        }

        Text(
            text = "${formatPercent(run.falsePositiveRate)} of different-person pairs were merged · " +
                    "${run.samePairs} same-person pairs · ${run.differentPairs} different-person pairs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ViewSwitcher(
    selected: LabView,
    peopleCount: Int,
    photoCount: Int,
    mistakeCount: Int,
    onSelect: (LabView) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ViewTab("People $peopleCount", selected == LabView.PEOPLE, Modifier.weight(1f)) {
            onSelect(LabView.PEOPLE)
        }
        ViewTab("Photos $photoCount", selected == LabView.PHOTOS, Modifier.weight(1f)) {
            onSelect(LabView.PHOTOS)
        }
        ViewTab("Mistakes $mistakeCount", selected == LabView.MISTAKES, Modifier.weight(1f)) {
            onSelect(LabView.MISTAKES)
        }
    }
}

@Composable
private fun ViewTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// -----------------------------------------------------------------------------
// People view
// -----------------------------------------------------------------------------

@Composable
private fun PersonCard(
    outcome: PersonOutcome,
    run: ThresholdRun,
    faces: List<IndexedValue<LabFace>>
) {
    val dominantGroup = outcome.dominant?.group ?: -1
    val accent = if (outcome.isPerfect) GoodColor else BadColor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Person ${outcome.person}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Pill(
                text = if (outcome.isPerfect) "one group" else "${outcome.groupCount} groups",
                color = accent
            )
        }

        Text(
            text = if (outcome.isPerfect) {
                "${outcome.faces} faces · all in ${groupTag(dominantGroup)}"
            } else {
                "${outcome.faces} faces · ${outcome.facesInDominant} in ${groupTag(dominantGroup)}, " +
                        "${outcome.facesElsewhere} elsewhere"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items = faces, key = { "pf_${it.index}" }) { indexed ->
                val group = run.groupOf.getOrNull(indexed.index) ?: -1
                val isStray = group != dominantGroup
                FaceChip(
                    face = indexed.value,
                    group = group,
                    accent = if (isStray) BadColor else GoodColor
                )
            }
        }

        if (!outcome.isPerfect) {
            Text(
                text = "Strays: " + outcome.groupCounts.drop(1)
                    .joinToString(" · ") { "${groupTag(it.group)} ×${it.count}" },
                style = MaterialTheme.typography.labelSmall,
                color = BadColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Photos view
// -----------------------------------------------------------------------------

@Composable
private fun PhotoTile(
    report: LabPhotoReport,
    faces: List<LabFace>,
    run: ThresholdRun,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    val verdict = photoVerdict(report, faces, run)

    Column(
        modifier = modifier
            .clip(TileShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = TileShape
            )
            .clickable { onToggle() }
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (report.imageWidth > 0) {
            PhotoWithBoxes(
                model = Uri.parse(report.photoId),
                imageWidth = report.imageWidth,
                imageHeight = report.imageHeight,
                faces = faces,
                faceColor = { face -> faceColor(face, run) }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(TileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("no preview", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(verdict.color)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = verdict.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = verdict.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = LAB_CATEGORY_MAP[report.categoryId]?.title ?: report.categoryId,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PhotoDetail(
    report: LabPhotoReport,
    faces: List<LabFace>,
    run: ThresholdRun
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = report.displayName,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "${LAB_CATEGORY_MAP[report.categoryId]?.title ?: ""} · " +
                    "detected ${report.rawDetections}, accepted ${report.acceptedDetections}, " +
                    "embedded ${report.embeddedFaces}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (report.imageWidth > 0) {
            PhotoWithBoxes(
                model = Uri.parse(report.photoId),
                imageWidth = report.imageWidth,
                imageHeight = report.imageHeight,
                faces = faces,
                faceColor = { face -> faceColor(face, run) }
            )
        }

        if (faces.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = faces, key = { "df_${it.detectionIndex}" }) { face ->
                    val group = run.groupOf.getOrNull(face.engineIndex) ?: -1
                    FaceChip(
                        face = face,
                        group = group,
                        accent = faceColor(face, run),
                        label = "${face.detectionIndex}·${groupTag(group)}"
                    )
                }
            }
        }

        if (report.error != null) {
            Text(report.error, style = MaterialTheme.typography.labelSmall, color = BadColor)
        }
        if (report.note != null) {
            Text(report.note, style = MaterialTheme.typography.labelSmall, color = WarnColor)
        }

        report.rejected.forEach { rejected ->
            Text(
                text = "Detection ${rejected.detectionIndex} rejected · ${rejected.reasons.joinToString("; ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Mistakes view
// -----------------------------------------------------------------------------

private sealed interface Mistake {
    val key: String
    val title: String
    val subtitle: String
}

private data class MergeMistake(val group: GroupInfo) : Mistake {
    override val key: String get() = "merge_${group.index}"
    override val title: String get() = "Two people put in one group"
    override val subtitle: String get() = "Group ${group.index} · ${group.compositionLabel}"
}

private data class SplitMistake(val outcome: PersonOutcome) : Mistake {
    override val key: String get() = "split_${outcome.person}"
    override val title: String get() = "One person split across groups"
    override val subtitle: String
        get() = "Person ${outcome.person} · " +
                outcome.groupCounts.joinToString(" · ") { "G${it.group} ×${it.count}" }
}

private data class CollideMistake(val collision: Collision) : Mistake {
    override val key: String get() = "collide_${collision.photoIndex}_${collision.group}"
    override val title: String get() = "Two faces of one photo merged"
    override val subtitle: String
        get() = "${collision.photoName} · detections " +
                collision.detectionIndices.joinToString(", ") + " → G${collision.group}"
}

private data class ComboMistake(val failure: ComboFailure) : Mistake {
    override val key: String get() = "combo_${failure.photoIndex}"
    override val title: String get() = "Combination photo misread"
    override val subtitle: String
        get() = "${failure.photoName} · expected " +
                failure.expected.joinToString(" + ") { "P$it" }
}

private fun countMistakes(run: ThresholdRun): Int =
    run.mergedGroups.size + run.splitPeople.size + run.collisions.size + run.comboFailures.size

private fun buildMistakes(run: ThresholdRun): List<Mistake> {
    val out = ArrayList<Mistake>()
    run.mergedGroups.forEach { out.add(MergeMistake(it)) }
    run.splitPeople.forEach { out.add(SplitMistake(it)) }
    run.collisions.forEach { out.add(CollideMistake(it)) }
    run.comboFailures.forEach { out.add(ComboMistake(it)) }
    return out
}

@Composable
private fun MistakeCard(
    mistake: Mistake,
    run: ThresholdRun,
    faces: List<LabFace>,
    reports: List<LabPhotoReport>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(TileShape)
            .background(BadColor.copy(alpha = 0.07f))
            .border(1.dp, BadColor.copy(alpha = 0.35f), TileShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = mistake.title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            color = BadColor
        )

        Text(
            text = mistake.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (mistake) {
            is MergeMistake -> MergeVisual(mistake.group, run, faces)

            is SplitMistake -> SplitVisual(mistake.outcome, run, faces)

            is CollideMistake -> PhotoEvidence(mistake.collision.photoIndex, run, faces, reports)

            is ComboMistake -> {
                Text(
                    text = mistake.failure.observed.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = BadColor
                )
                Text(
                    text = mistake.failure.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PhotoEvidence(mistake.failure.photoIndex, run, faces, reports)
            }
        }
    }
}

@Composable
private fun MergeVisual(group: GroupInfo, run: ThresholdRun, faces: List<LabFace>) {
    val byPerson = remember(group, run, faces) {
        group.knownCounts.associate { count ->
            count.person to faces.filter { face ->
                face.engineIndex >= 0 &&
                        run.groupOf.getOrNull(face.engineIndex) == group.index &&
                        face.knownPerson == count.person
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        group.knownCounts.forEach { count ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "P${count.person}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = BadColor,
                    modifier = Modifier.width(32.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(
                        items = byPerson[count.person] ?: emptyList(),
                        key = { "mg_${it.engineIndex}" }
                    ) { face ->
                        FaceChip(face = face, group = group.index, accent = BadColor, size = 46)
                    }
                }
            }
        }

        Text(
            text = "${group.faceCount} faces share this one group.",
            style = MaterialTheme.typography.labelSmall,
            color = BadColor
        )
    }
}

@Composable
private fun SplitVisual(outcome: PersonOutcome, run: ThresholdRun, faces: List<LabFace>) {
    val byGroup = remember(outcome, run, faces) {
        outcome.groupCounts.associate { count ->
            count.group to faces.filter { face ->
                face.engineIndex >= 0 &&
                        face.knownPerson == outcome.person &&
                        run.groupOf.getOrNull(face.engineIndex) == count.group
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        outcome.groupCounts.forEachIndexed { position, count ->
            val accent = if (position == 0) GoodColor else BadColor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = groupTag(count.group),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    modifier = Modifier.width(32.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(
                        items = byGroup[count.group] ?: emptyList(),
                        key = { "sp_${it.engineIndex}" }
                    ) { face ->
                        FaceChip(face = face, group = count.group, accent = accent, size = 46)
                    }
                }
            }
        }

        Text(
            text = "${outcome.facesElsewhere} of ${outcome.faces} faces drifted away from the main group.",
            style = MaterialTheme.typography.labelSmall,
            color = BadColor
        )
    }
}

@Composable
private fun PhotoEvidence(
    photoIndex: Int,
    run: ThresholdRun,
    faces: List<LabFace>,
    reports: List<LabPhotoReport>
) {
    val report = reports.getOrNull(photoIndex) ?: return
    val photoFaces = remember(photoIndex, faces) { faces.filter { it.photoIndex == photoIndex } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (report.imageWidth > 0) {
            PhotoWithBoxes(
                model = Uri.parse(report.photoId),
                imageWidth = report.imageWidth,
                imageHeight = report.imageHeight,
                faces = photoFaces,
                faceColor = { face -> faceColor(face, run) }
            )
        }

        if (photoFaces.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(items = photoFaces, key = { "ev_${it.engineIndex}" }) { face ->
                    val group = run.groupOf.getOrNull(face.engineIndex) ?: -1
                    FaceChip(
                        face = face,
                        group = group,
                        accent = faceColor(face, run),
                        size = 54,
                        label = "${face.detectionIndex}·${groupTag(group)}"
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Shared small pieces
// -----------------------------------------------------------------------------

private class Verdict(val label: String, val color: Color)

private fun photoVerdict(
    report: LabPhotoReport,
    faces: List<LabFace>,
    run: ThresholdRun
): Verdict {
    if (report.error != null) return Verdict("unreadable", BadColor)
    if (faces.isEmpty()) return Verdict("no face", WarnColor)

    val category = LAB_CATEGORY_MAP[report.categoryId]

    if (category != null && category.kind == LabCategoryKind.SINGLE) {
        val known = faces.firstOrNull()?.knownPerson
        if (known == null) return Verdict("${faces.size} faces · not scored", WarnColor)
        val group = run.groupOf.getOrNull(faces.first().engineIndex) ?: -1
        val inferred = run.groups.getOrNull(group)?.inferredPerson
        return when {
            inferred == null -> Verdict("group unidentified", WarnColor)
            inferred == known -> Verdict("correct", GoodColor)
            else -> Verdict("wrong person", BadColor)
        }
    }

    if (faces.size > 1) {
        val groups = faces.map { run.groupOf.getOrNull(it.engineIndex) ?: -1 }
        if (groups.distinct().size < groups.size) return Verdict("faces merged", BadColor)
    }

    val wrong = faces.count { face ->
        val group = run.groupOf.getOrNull(face.engineIndex) ?: -1
        val inferred = run.groups.getOrNull(group)?.inferredPerson ?: return@count false
        inferred !in face.allowedPeople
    }

    return if (wrong > 0) Verdict("$wrong wrong", BadColor) else Verdict("consistent", GoodColor)
}

private fun faceColor(face: LabFace, run: ThresholdRun): Color {
    val group = run.groupOf.getOrNull(face.engineIndex) ?: return WarnColor
    val inferred = run.groups.getOrNull(group)?.inferredPerson ?: return WarnColor

    val known = face.knownPerson
    if (known != null) return if (inferred == known) GoodColor else BadColor
    return if (inferred in face.allowedPeople) GoodColor else BadColor
}

@Composable
private fun ProgressBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val safe = fraction.coerceIn(0f, 1f)
        if (safe > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safe)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
    )
}
