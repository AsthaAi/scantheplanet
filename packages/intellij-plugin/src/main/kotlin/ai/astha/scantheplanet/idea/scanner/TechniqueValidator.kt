package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

class TechniqueValidator(private val repository: SafeMcpRepository) {
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    private val jsonMapper = ObjectMapper()
    private val schema: JsonSchema? = loadSchema()

    data class ValidationError(val path: String, val messages: List<String>)

    data class ValidationResult(
        val techniques: List<TechniqueSpec>,
        val errors: List<ValidationError>
    )

    fun validateAll(): ValidationResult {
        val ids = repository.listTechniqueIds()
        val techniques = mutableListOf<TechniqueSpec>()
        val errors = mutableListOf<ValidationError>()

        for (id in ids) {
            val raw = repository.readTechniqueSpec(id)
            if (raw == null) {
                errors.add(ValidationError(id, listOf("technique spec not found")))
                continue
            }
            val jsonNode = try {
                yamlMapper.readTree(raw)
            } catch (e: Exception) {
                errors.add(ValidationError(id, listOf("failed to parse yaml: ${e.message}")))
                continue
            }
            if (schema != null) {
                val violations = schema.validate(jsonNode)
                if (violations.isNotEmpty()) {
                    errors.add(ValidationError(id, violations.map { it.message }))
                    continue
                }
            }
            val technique = try {
                yamlMapper.readValue(raw, TechniqueSpec::class.java)
            } catch (e: Exception) {
                errors.add(ValidationError(id, listOf("failed to deserialize: ${e.message}")))
                continue
            }
            techniques.add(technique)
        }

        return ValidationResult(techniques.sortedBy { it.id }, errors)
    }

    private fun loadSchema(): JsonSchema? {
        val raw = repository.readText("schemas/technique.schema.json") ?: return null
        val node = jsonMapper.readTree(raw)
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
        return factory.getSchema(node)
    }
}
