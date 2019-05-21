package com.dc2f.api.edit

import com.fasterxml.jackson.core.ObjectCodec
import com.fasterxml.jackson.core.io.IOContext
import com.fasterxml.jackson.dataformat.yaml.*
import org.yaml.snakeyaml.DumperOptions
import java.io.Writer

class Dc2fYAMLFactory : YAMLFactory() {
    override fun _createGenerator(out: Writer?, ctxt: IOContext?): YAMLGenerator {
        val feats = _yamlGeneratorFeatures
        // any other initializations? No?
        return Dc2fYAMLGenerator(
            ctxt, _generatorFeatures, feats,
            _objectCodec, out, _version
        )

    }
}

class Dc2fYAMLGenerator(
    ctxt: IOContext?,
    jsonFeatures: Int,
    yamlFeatures: Int,
    codec: ObjectCodec?,
    out: Writer?,
    version: DumperOptions.Version?
) : YAMLGenerator(ctxt, jsonFeatures, yamlFeatures, codec, out, version) {

    companion object {
        // Strings quoted for fun
        private val STYLE_QUOTED = Character.valueOf('"')

        private val STYLE_QUOTED_OVERRIDE = Character.valueOf('\'')
    }


    override fun _writeScalar(value: String?, type: String?, style: Char?) {
        super._writeScalar(
            value, type, if (style == STYLE_QUOTED) {
                STYLE_QUOTED_OVERRIDE
            } else {
                style
            }
        )
    }

    override fun buildDumperOptions(
        jsonFeatures: Int,
        yamlFeatures: Int,
        version: DumperOptions.Version?
    ): DumperOptions = super.buildDumperOptions(jsonFeatures, yamlFeatures, version).apply {
        indicatorIndent = 0
        indent = 2
    }
}