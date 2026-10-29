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
import java.math.BigDecimal
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
private fun fieldType(schema: JSONObject) = schema.string("type").ifBlank { if (mcpOptions(schema).isNotEmpty()) "string" else "" }
private fun fieldPath(parent: String, key: String) = (if (parent.isEmpty()) "" else "$parent/") + key.replace("~", "~0").replace("/", "~1")
private fun stringArray(schema: JSONObject, key: String): Boolean = schema.optJSONArray(key)?.let { values -> (0 until values.length()).all { values.opt(it) is String } } == true
private fun validStringOptions(schema: JSONObject): Boolean {
    if (listOf("enum", "oneOf", "anyOf").count(schema::has) > 1) return false
    if (schema.has("enum") && (!stringArray(schema, "enum") || schema.getJSONArray("enum").length() == 0)) return false
    if (schema.has("enumNames") && (!stringArray(schema, "enumNames") || schema.optJSONArray("enumNames")?.length() != schema.optJSONArray("enum")?.length())) return false
    for (key in listOf("oneOf", "anyOf")) if (schema.has(key)) {
        val options = schema.optJSONArray(key) ?: return false
        if (options.length() == 0 || (0 until options.length()).any { index ->
            val option = options.optJSONObject(index)
            option == null || option.opt("const") !is String || option.keys().asSequence().any { it !in setOf("const", "title") }
        }) return false
    }
    return true
}

/** A deliberately checked JSON Schema subset. Unknown constraints never become an unchecked form. */
internal fun supportedMcpForm(schema: JSONObject): Boolean {
    var fields = 0
    fun supported(field: JSONObject, depth: Int): Boolean {
        if (depth > 6 || ++fields > 80) return false
        val type = fieldType(field)
        val common = setOf("\$schema", "type", "title", "description", "default")
        val allowed = common + when (type) {
            "object" -> setOf("properties", "required", "additionalProperties")
            "string" -> setOf("minLength", "maxLength", "format", "isSecret", "enum", "enumNames", "oneOf", "anyOf")
            "number", "integer" -> setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")
            "boolean" -> emptySet()
            "array" -> setOf("minItems", "maxItems", "items", "uniqueItems")
            else -> return false
        }
        if (field.keys().asSequence().any { it !in allowed }) return false
        if (listOf("minLength", "maxLength", "minItems", "maxItems").any { key -> field.has(key) &&
                (field.opt(key) !is Number || field.optDouble(key) < 0 || !field.optDouble(key).isFinite() || field.optDouble(key) % 1.0 != 0.0 || field.optDouble(key) > Int.MAX_VALUE) }) return false
        if (listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf").any { key ->
                field.has(key) && (field.opt(key) !is Number || !field.optDouble(key).isFinite()) }) return false
        if (field.has("multipleOf") && field.optDouble("multipleOf") <= 0) return false
        if (field.has("default") && when (type) {
            "object" -> field.opt("default") !is JSONObject
            "array" -> field.opt("default") !is JSONArray
            "string" -> field.opt("default") !is String
            "boolean" -> field.opt("default") !is Boolean
            "number", "integer" -> field.opt("default") !is Number
            else -> true
        }) return false
        return when (type) {
            "object" -> {
                val properties = field.optJSONObject("properties") ?: return false
                (!field.has("additionalProperties") || field.opt("additionalProperties") is Boolean) &&
                    (!field.has("required") || stringArray(field, "required")) && field.strings("required").all(properties::has) &&
                    properties.keys().asSequence().all { key -> properties.optJSONObject(key)?.let { supported(it, depth + 1) } == true }
            }
            "string" -> (!field.has("format") || field.string("format") in setOf("email", "uri", "date", "date-time", "password")) && validStringOptions(field)
            "array" -> (!field.has("uniqueItems") || field.opt("uniqueItems") is Boolean) && field.optInt("minItems", 0) <= 100 &&
                field.optJSONObject("items")?.let { supported(it, depth + 1) } == true
            else -> true
        }
    }
    return schema.string("type") == "object" && runCatching { supported(schema, 0) }.getOrDefault(false)
}

internal fun mcpFormContent(schema: JSONObject, values: Map<String, String>, multi: Map<String, Set<String>>): JSONObject {
    require(supportedMcpForm(schema)) { "此表单需要在 Desktop 处理" }
    fun content(field: JSONObject, path: String, required: Boolean, label: String): Any? {
        val type = fieldType(field)
        if (type == "object") {
            val presence = values["$path/@present"]
            if (path.isNotEmpty() && !required && (presence == "false" || presence != "true" && values.keys.none { it.startsWith("$path/") } && multi.keys.none { it.startsWith("$path/") })) return null
            val result = JSONObject()
            val properties = field.getJSONObject("properties")
            val requiredKeys = field.strings("required").toSet()
            properties.keys().forEach { key ->
                val child = properties.getJSONObject(key)
                content(child, fieldPath(path, key), key in requiredKeys, child.string("title").ifBlank { key })?.let { result.put(key, it) }
            }
            return result
        }
        if (type == "array") {
            val item = field.getJSONObject("items")
            val options = mcpOptions(item)
            val result = JSONArray()
            if (fieldType(item) == "string" && options.isNotEmpty()) {
                val chosen = multi[path].orEmpty()
                if (chosen.isEmpty() && !required) return null
                require(chosen.all { value -> options.any { it.first == value } }) { "$label 包含无效选项" }
                chosen.forEach(result::put)
            } else {
                val count = values["$path/@count"]?.toIntOrNull() ?: 0
                require(count in 0..100) { "$label 的项目数量过多" }
                if (count == 0 && !required) return null
                repeat(count) { index -> result.put(content(item, fieldPath(path, index.toString()), true, "$label 第 ${index + 1} 项")) }
            }
            require(result.length() >= field.optInt("minItems", 0) && result.length() <= field.optInt("maxItems", Int.MAX_VALUE)) { "$label 的项目数量不符合要求" }
            if (field.optBoolean("uniqueItems")) {
                val unique = (0 until result.length()).map { canonicalFormValue(result.get(it)) }.toSet()
                require(unique.size == result.length()) { "$label 不能包含重复项目" }
            }
            return result
        }
        val value = values[path]
        if (value == null) { require(!required) { "请填写 $label" }; return null }
        return when (type) {
            "boolean" -> { require(value in setOf("true", "false")) { "请选择 $label" }; value.toBoolean() }
            "number", "integer" -> {
                require(value.length <= 256) { "$label 数字过长" }
                val number = value.toBigDecimalOrNull(); require(number != null && kotlin.math.abs(number.scale().toLong()) <= 1000) { "$label 需要有效数字" }
                if (type == "integer") require(number.stripTrailingZeros().scale() <= 0) { "$label 需要整数" }
                fun bound(key: String) = field.get(key).toString().toBigDecimal()
                if (field.has("minimum")) require(number >= bound("minimum")) { "$label 小于允许的最小值" }
                if (field.has("maximum")) require(number <= bound("maximum")) { "$label 超出允许的最大值" }
                if (field.has("exclusiveMinimum")) require(number > bound("exclusiveMinimum")) { "$label 需要大于 ${field.get("exclusiveMinimum")}" }
                if (field.has("exclusiveMaximum")) require(number < bound("exclusiveMaximum")) { "$label 需要小于 ${field.get("exclusiveMaximum")}" }
                if (field.has("multipleOf")) require(number.remainder(bound("multipleOf")).compareTo(BigDecimal.ZERO) == 0) { "$label 的步长不正确" }
                if (type == "integer") number.toBigIntegerExact() else number
            }
            else -> {
                val length = value.codePointCount(0, value.length)
                if (field.has("minLength")) require(length >= field.getInt("minLength")) { "$label 长度不足" }
                if (field.has("maxLength")) require(length <= field.getInt("maxLength")) { "$label 过长" }
                val options = mcpOptions(field); if (options.isNotEmpty()) require(options.any { it.first == value }) { "请选择 $label" }
                val valid = when (field.string("format")) {
                    "email" -> Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(value)
                    "uri" -> runCatching { URI(value).isAbsolute }.getOrDefault(false)
                    "date" -> runCatching { LocalDate.parse(value) }.isSuccess
                    "date-time" -> runCatching { OffsetDateTime.parse(value) }.isSuccess
                    else -> true
                }
                require(valid) { "$label 格式不正确" }; value
            }
        }
    }
    return content(schema, "", true, "表单") as JSONObject
}

private fun canonicalFormValue(value: Any): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonicalFormValue(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalFormValue(value.get(it)) }
    is Number -> value.toString().toBigDecimal().stripTrailingZeros().toString()
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

private fun initializeForm(field: JSONObject, path: String, values: MutableMap<String, String>, multi: MutableMap<String, Set<String>>, required: Boolean, inherited: Any? = null) {
    val default = inherited ?: field.opt("default")
    when (fieldType(field)) {
        "object" -> {
            if (path.isNotEmpty() && !required && default !is JSONObject) return
            if (path.isNotEmpty()) values["$path/@present"] = "true"
            val properties = field.getJSONObject("properties")
            properties.keys().forEach { key -> initializeForm(properties.getJSONObject(key), fieldPath(path, key), values, multi, key in field.strings("required"), (default as? JSONObject)?.opt(key)) }
        }
        "array" -> {
            val items = field.getJSONObject("items")
            if (fieldType(items) == "string" && mcpOptions(items).isNotEmpty()) {
                if (default is JSONArray) multi[path] = (0 until default.length()).map { default.optString(it) }.toSet()
            } else {
                val count = ((default as? JSONArray)?.length() ?: if (required) field.optInt("minItems", 0) else 0).coerceAtMost(100)
                values["$path/@count"] = count.toString()
                repeat(count) { index -> initializeForm(items, fieldPath(path, index.toString()), values, multi, true, (default as? JSONArray)?.opt(index)) }
            }
        }
        else -> if (default != null && default != JSONObject.NULL && !values.containsKey(path)) values[path] = default.toString()
    }
}

@Composable
internal fun McpForm(schema: JSONObject, enabled: Boolean, onSubmit: (JSONObject) -> Unit) {
    val values = remember(schema.toString()) { mutableStateMapOf<String, String>() }
    val multi = remember(schema.toString()) { mutableStateMapOf<String, Set<String>>() }
    remember(schema.toString()) { initializeForm(schema, "", values, multi, true); true }
    var error by remember(schema.toString()) { mutableStateOf<String?>(null) }
    McpField(schema, "", "", true, enabled, values, multi) { error = null }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { try { onSubmit(mcpFormContent(schema, values, multi)) } catch (e: IllegalArgumentException) { error = e.message } }, enabled = enabled) { Text("提交表单") }
}

@Composable
private fun McpField(field: JSONObject, path: String, name: String, required: Boolean, enabled: Boolean,
    values: MutableMap<String, String>, multi: MutableMap<String, Set<String>>, edited: () -> Unit) {
    val type = fieldType(field)
    val label = field.string("title").ifBlank { name } + if (required) " *" else ""
    if (path.isNotEmpty()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        field.string("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    val options = mcpOptions(field)
    when {
        type == "object" -> {
            if (path.isNotEmpty() && !required) FilterChip(values["$path/@present"] == "true", {
                val include = values["$path/@present"] != "true"
                values["$path/@present"] = include.toString()
                if (include) initializeForm(field, path, values, multi, true)
                edited()
            }, label = { Text(if (values["$path/@present"] == "true") "填写此项" else "不填写此项") }, enabled = enabled)
            if (path.isEmpty() || required || values["$path/@present"] == "true") Column(Modifier.padding(start = if (path.isEmpty()) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val properties = field.getJSONObject("properties")
                properties.keys().forEach { key -> McpField(properties.getJSONObject(key), fieldPath(path, key), key, key in field.strings("required"), enabled, values, multi, edited) }
            }
        }
        type == "boolean" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(values[path] == "true", { values[path] = "true"; edited() }, label = { Text("是") }, enabled = enabled)
            FilterChip(values[path] == "false", { values[path] = "false"; edited() }, label = { Text("否") }, enabled = enabled)
        }
        type == "array" -> {
            val item = field.getJSONObject("items")
            val choices = mcpOptions(item)
            if (fieldType(item) == "string" && choices.isNotEmpty()) choices.forEach { (value, title) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(value in multi[path].orEmpty(), { checked -> multi[path] = if (checked) multi[path].orEmpty() + value else multi[path].orEmpty() - value; edited() }, enabled = enabled)
                    Text(title)
                }
            } else {
                val count = values["$path/@count"]?.toIntOrNull() ?: 0
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(count) { index -> McpField(item, fieldPath(path, index.toString()), "第 ${index + 1} 项", true, enabled, values, multi, edited) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { initializeForm(item, fieldPath(path, count.toString()), values, multi, true); values["$path/@count"] = (count + 1).toString(); edited() }, enabled = enabled && count < minOf(100, field.optInt("maxItems", 100))) { Text("添加一项") }
                        if (count > 0) TextButton(onClick = { val prefix = fieldPath(path, (count - 1).toString()); values.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach(values::remove); multi.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach(multi::remove); values["$path/@count"] = (count - 1).toString(); edited() }, enabled = enabled) { Text("移除末项") }
                    }
                }
            }
        }
        options.isNotEmpty() -> {
            var expanded by remember(path) { mutableStateOf(false) }
            Box { OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == values[path] }?.second ?: "请选择") }
                DropdownMenu(expanded, { expanded = false }) { options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { values[path] = value; expanded = false; edited() }) } }
            }
        }
        else -> OutlinedTextField(values[path].orEmpty(), { values[path] = it; edited() }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            singleLine = true, visualTransformation = if (field.optBoolean("isSecret") || field.string("format") == "password") PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (field.optBoolean("isSecret") || field.string("format") == "password") KeyboardType.Password else if (type in setOf("number", "integer")) KeyboardType.Decimal else if (field.string("format") == "email") KeyboardType.Email else KeyboardType.Text))
    }
}
