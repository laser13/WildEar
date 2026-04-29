package com.sound2inat.inference

import java.io.File

/** Single BirdNET label parsed from `Scientific Name_Common Name` lines. */
data class Label(val scientificName: String, val commonName: String?)

object Labels {
    /**
     * Reads BirdNET-Analyzer-format labels from [file]. Each non-blank line is
     * `Scientific name_Common Name` (underscore separator). If no underscore is
     * present (e.g. "Noise"), [Label.commonName] is null.
     */
    fun load(file: File): List<Label> = file.readLines()
        .map { it.trim() }
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
}
