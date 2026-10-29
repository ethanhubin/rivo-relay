package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.objects
import dev.rivo.relay.data.string
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime

internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
internal fun mcpOptions(schema: JSONObject): List<Pair<String, String>> = when {
    schema.has("oneOf") -> schema.objects("oneOf").map { it.string("const") to it.string("title").ifBlank { it.string("const") } }
    schema.has("anyOf") -> schema.objects("anyOf").map { it.string("const") to it.string("title").ifBlank { it.string("const") } }
    schema.has("enum") -> schema.strings("enum").mapIndexed { i, value -> value to (schema.optJSONArray("enumNames")?.optString(i)?.takeIf { it.isNotBlank() } ?: value) }
    else -> emptyList()
}
internal fun supportedMcpForm(schema: JSONObject): Boolean {
    if (schema.string("type") != "object") return false
    if (schema.keys().asSequence().any { it !in setOf("\$schema", "type", "properties", "required", "title", "description", "additionalProperties") }) return false
    if (schema.has("additionalProperties") && schema.opt("additionalProperties") !is Boolean) return false
    val properties = schema.optJSONObject("properties") ?: return false
    if (schema.has("required") && !stringArray(schema, "required")) return false
    if (schema.strings("required").any { !properties.has(it) }) return false
    return properties.keys().asSequence().all { key ->
        val field = properties.optJSONObject(key) ?: return@all false
        val type = field.string("type")
        val common = setOf("type", "title", "description", "default")
        val allowed = common + when (type) {
            "string" -> setOf("minLength", "maxLength", "format", "isSecret", "enum", "enumNames", "oneOf")
            "number", "integer" -> setOf("minimum", "maximum")
            "boolean" -> emptySet()
            "array" -> setOf("minItems", "maxItems", "items")
            else -> return@all false
        }
        if (field.keys().asSequence().any { it !in allowed }) return@all false
        if (listOf("minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems").any { field.has(it) && field.opt(it) !is Number }) return@all false
        if (field.has("default") && when (type) {
            "string" -> field.opt("default") !is String
            "number", "integer" -> field.opt("default") !is Number
            "boolean" -> field.opt("default") !is Boolean
            "array" -> !stringArray(field, "default")
            else -> true
        }) return@all false
        when (field.string("type")) {
            "string" -> (!field.has("format") || field.string("format") in setOf("email", "uri", "date", "date-time", "password")) && validStringOptions(field)
            "number", "integer", "boolean" -> true
            "array" -> field.optJSONObject("items")?.let { item ->
                item.keys().asSequence().all { it in setOf("type", "enum", "anyOf") } && (!item.has("type") || item.string("type") == "string") && validStringOptions(item) && mcpOptions(item).isNotEmpty()
            } == true
            else -> false
        }
    }
}
private fun stringArray(schema: JSONObject, key: String): Boolean = schema.optJSONArray(key)?.let { values -> (0 until values.length()).all { values.opt(it) is String } } == true
private fun validStringOptions(schema: JSONObject): Boolean {
    if (schema.has("enum") && !stringArray(schema, "enum")) return false
    if (schema.has("enumNames") && !stringArray(schema, "enumNames")) return false
    for (key in listOf("oneOf", "anyOf")) if (schema.has(key)) {
        val options = schema.optJSONArray(key) ?: return false
        if (options.length() == 0 || (0 until options.length()).any { index ->
            val option = options.optJSONObject(index)
            option == null || option.opt("const") !is String || option.keys().asSequence().any { it !in setOf("const", "title") }
        }) return false
    }
    return true
}
internal fun mcpFormContent(schema: JSONObject, values: Map<String, String>, multi: Map<String, Set<String>>): JSONObject {
    require(supportedMcpForm(schema)) { "此表单需要在 Desktop 处理" }
    val properties = schema.getJSONObject("properties")
    val required = schema.strings("required").toSet()
    val result = JSONObject()
    properties.keys().forEach { key ->
        val field = properties.getJSONObject(key)
        val label = field.string("title").ifBlank { key }
        val value = values[key].orEmpty()
        val type = field.string("type")
        if (type == "array") {
            val chosen = multi[key].orEmpty()
            if (chosen.isEmpty() && key !in required) return@forEach
            val minimum = if (field.has("minItems")) field.getInt("minItems") else 0
            val maximum = if (field.has("maxItems")) field.getInt("maxItems") else Int.MAX_VALUE
            require(chosen.size in minimum..maximum) { "$label 的选项数量不符合要求" }
            val allowed = mcpOptions(field.getJSONObject("items")).map { it.first }.toSet()
            require(chosen.all { it in allowed }) { "$label 包含无效选项" }
            result.put(key, JSONArray(chosen.toList())); return@forEach
        }
        if (value.isBlank()) { require(key !in required) { "请填写 $label" }; return@forEach }
        when (type) {
            "boolean" -> { require(value in setOf("true", "false")) { "请选择 $label" }; result.put(key, value.toBoolean()) }
            "number", "integer" -> {
                val number = value.toDoubleOrNull(); require(number != null && number.isFinite()) { "$label 需要数字" }
                if (type == "integer") require(number % 1.0 == 0.0) { "$label 需要整数" }
                if (field.has("minimum")) require(number >= field.getDouble("minimum")) { "$label 小于允许的最小值" }
                if (field.has("maximum")) require(number <= field.getDouble("maximum")) { "$label 超出允许的最大值" }
                result.put(key, if (type == "integer") number.toLong() else number)
            }
            "string" -> {
                if (field.has("minLength")) require(value.length >= field.getInt("minLength")) { "$label 长度不足" }
                if (field.has("maxLength")) require(value.length <= field.getInt("maxLength")) { "$label 过长" }
                val options = mcpOptions(field); if (options.isNotEmpty()) require(options.any { it.first == value }) { "请选择 $label" }
                val valid = when (field.string("format")) {
                    "email" -> Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(value)
                    "uri" -> runCatching { URI(value).isAbsolute }.getOrDefault(false)
                    "date" -> runCatching { LocalDate.parse(value) }.isSuccess
                    "date-time" -> runCatching { OffsetDateTime.parse(value) }.isSuccess
                    else -> true
                }
                require(valid) { "$label 格式不正确" }; result.put(key, value)
            }
        }
    }
    return result
}

@Composable
internal fun McpForm(schema: JSONObject, enabled: Boolean, onSubmit: (JSONObject) -> Unit) {
    val properties = schema.getJSONObject("properties")
    val values = remember(schema.toString()) { mutableStateMapOf<String, String>().apply {
        properties.keys().forEach { key -> val field = properties.getJSONObject(key); if (field.has("default") && field.string("type") != "array") put(key, field.get("default").toString()) }
    } }
    val multi = remember(schema.toString()) { mutableStateMapOf<String, Set<String>>().apply { properties.keys().forEach { key -> val field = properties.getJSONObject(key); if (field.string("type") == "array") put(key, field.strings("default").toSet()) } } }
    var error by remember { mutableStateOf<String?>(null) }
    properties.keys().forEach { key ->
        val field = properties.getJSONObject(key)
        val label = field.string("title").ifBlank { key } + if (key in schema.strings("required")) " *" else ""
        Text(label, style = MaterialTheme.typography.labelMedium)
        field.string("description").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val options = mcpOptions(field)
        when {
            field.string("type") == "boolean" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(values[key] == "true", { values[key] = "true" }, label = { Text("是") }, enabled = enabled)
                FilterChip(values[key] == "false", { values[key] = "false" }, label = { Text("否") }, enabled = enabled)
            }
            field.string("type") == "array" -> mcpOptions(field.getJSONObject("items")).forEach { (value, title) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(value in multi[key].orEmpty(), { checked -> multi[key] = if (checked) multi[key].orEmpty() + value else multi[key].orEmpty() - value }, enabled = enabled)
                    Text(title)
                }
            }
            options.isNotEmpty() -> {
                var expanded by remember(key) { mutableStateOf(false) }
                Box { OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == values[key] }?.second ?: "请选择") }
                    DropdownMenu(expanded, { expanded = false }) { options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { values[key] = value; expanded = false }) } }
                }
            }
            else -> OutlinedTextField(values[key].orEmpty(), { values[key] = it; error = null }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                singleLine = true, visualTransformation = if (field.optBoolean("isSecret") || field.string("format") == "password") PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (field.optBoolean("isSecret") || field.string("format") == "password") KeyboardType.Password else if (field.string("type") in setOf("number", "integer")) KeyboardType.Decimal else if (field.string("format") == "email") KeyboardType.Email else KeyboardType.Text))
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { try { onSubmit(mcpFormContent(schema, values, multi)) } catch (e: IllegalArgumentException) { error = e.message } }, enabled = enabled) { Text("提交表单") }
}
