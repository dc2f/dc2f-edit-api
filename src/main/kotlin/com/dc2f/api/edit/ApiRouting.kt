package com.dc2f.api.edit

import app.anlage.site.contentdef.*
import com.dc2f.*
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.call
import io.ktor.features.NotFoundException
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.KtorExperimentalAPI
import java.nio.file.Files


@KtorExperimentalAPI
fun Route.apiRouting(deps: Deps<*>) {
    get("/") {
        call.respondText("Hello world.")
    }
    route("/api") {
        get("/reflect/{path...}") {
            val path =
                call.parameters.getAll("path")?.joinToString("/")
                    ?: throw NotFoundException()
            val parameters = call.parameters

            val content =
                deps.context.contentByPath[ContentPath.parse(path)] ?: throw NotFoundException("Unable to find rootContent by path.")
            val metadata = deps.content.metadata.childrenMetadata[content] ?: throw NotFoundException("Unable to find metadata.")
            val fsPath = requireNotNull(metadata.fsPath)
            val om = jacksonObjectMapper()
                .also { it.configureObjectMapper() }
                .registerModule(SimpleModule().setSerializerModifier(ContentDefSerializerModifier()))

            val tree = ObjectMapper(YAMLFactory()).readTree(Files.readAllBytes(fsPath))
            val root = om.createObjectNode();
            root.set("content", tree)

            root.set("children", om.createObjectNode().also { obj ->
                obj.setAll(metadata.directChildren.mapValues {
                    val (key, value) = it
                    om.createArrayNode().also { arr ->
                        arr.addAll(value.map { contentDefChild ->
                            om.createObjectNode().apply {
                                put("path", contentDefChild.loadedContent.metadata.path.toString())
                                put("isProperty", contentDefChild.isProperty)
                            }
                        })
                    }
                })
            });

//            root.set("children")
//            om.writeValueAsString(root)
//            call.respond(om.writeValueAsString(InspectDto(rootContent)))
            call.respond(om.writeValueAsString(root))
        }
    }
}

class ContentDefSerializer : JsonSerializer<ContentDef>() {
    override fun serialize(
        value: ContentDef?,
        gen: JsonGenerator?,
        serializers: SerializerProvider?
    ) {
//        if (value is WebsiteFolderContent) {
//            serializers?.defaultSerializeValue(value, gen)
//        } else {
        gen?.writeString("blubb")
//        }
    }

    override fun handledType(): Class<ContentDef> {
        return ContentDef::class.java
    }

}

class ContentDefSerializerModifier : BeanSerializerModifier() {
    override fun modifySerializer(
        config: SerializationConfig?,
        beanDesc: BeanDescription,
        serializer: JsonSerializer<*>
    ): JsonSerializer<*> {
        if (
            ContentDef::class.java.isAssignableFrom(beanDesc.beanClass) &&
            !WebsiteFolderContent::class.java.isAssignableFrom(beanDesc.beanClass)
        ) {
            return ContentDefSerializer()
        }
        return serializer
    }
}
