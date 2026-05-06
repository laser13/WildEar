package com.sound2inat.inference

import com.sound2inat.modelmanager.LabelsFormat
import java.io.File

/** Single label parsed from a model's labels file. */
data class Label(val scientificName: String, val commonName: String?)

object Labels {
    /**
     * Reads labels from [file] using the model's [format]. The file's row
     * order must match the model's output tensor order — index N here is
     * read directly from `output[N]`.
     */
    fun load(file: File, format: LabelsFormat = LabelsFormat.BirdNetUnderscore): List<Label> =
        file.readLines()
            .map { it.trim() }
            .let { lines ->
                when (format) {
                    LabelsFormat.BirdNetUnderscore -> parseBirdNet(lines)
                    LabelsFormat.PerchScientificName -> parsePerch(lines)
                }
            }

    /**
     * BirdNET v2.4: `Scientific name_Common Name`, no header. Blank lines
     * are dropped (positional indexing happens after the drop because the
     * file ships without blanks anyway).
     */
    private fun parseBirdNet(lines: List<String>): List<Label> = lines
        .filter { it.isNotEmpty() }
        .map { line ->
            val sep = line.indexOf('_')
            if (sep < 0) {
                Label(line, null)
            } else {
                Label(
                    scientificName = line.substring(0, sep),
                    commonName = line.substring(sep + 1).ifBlank { null },
                )
            }
        }

    /**
     * Perch v2: bare scientific name per row. Row 0 is a CSV header/dataset
     * tag (`inat2024_fsd50k`) that has no corresponding output neuron — drop
     * it so `labels.size` (14795) matches the model's output tensor shape.
     */
    private fun parsePerch(lines: List<String>): List<Label> = lines
        .drop(1)
        .filter { it.isNotEmpty() }
        .map { name -> Label(name, null) }
}
