package dev.znci.rocket.scripting.api

import dev.znci.rocket.scripting.api.annotations.RocketNativeFunction
import dev.znci.rocket.scripting.api.annotations.RocketNativeProperty
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import java.util.ArrayList
import kotlin.reflect.*
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Abstract class RocketNative serves as a bridge between Kotlin and Lua, allowing functions and properties
 * to be dynamically registered in a Lua table.
 *
 * Code is written as Kotlin, and is converted to Lua if the appropriate function/property has the correct annotation.
 *
 * Functions with the {@code RocketNativeFunction} annotation will be registered, and properties with the {@code RocketNativeProperty} annotation.
 */
@Suppress("unused")
abstract class RocketNative(
    /** The name of the Lua table/property for this object. */
    override var valueName: String
) : RocketTable(valueName) {

    /**
     * Initializes the RocketNative instance by registering its functions and properties.
     */
    init {
        registerFunctions(this.table)
        registerProperties(this.table)
    }

    /**
     * Registers functions annotated with {@code RocketNativeFunction} into the Lua table.
     *
     * @param table The LuaTable instance to register functions to.
     */
    private fun registerFunctions(table: LuaTable) {
        this::class.functions.forEach { function ->
            if (function.findAnnotation<RocketNativeFunction>() == null) {
                return@forEach
            }
            val annotation = function.findAnnotation<RocketNativeFunction>()
            var annotatedFunctionName = annotation?.name ?: function.name

            if (annotatedFunctionName == "INHERIT_FROM_DEFINITION" ) {
                annotatedFunctionName = function.name
            }

            table.set(annotatedFunctionName, object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    return try {
                        val kotlinArgs = args.toKotlinArgs(function)
                        val result = function.call(this@RocketNative, *kotlinArgs)
                        result.toLuaValue()
                    } catch (e: Exception) {
                        error("Error calling ${function.name}: ${e.message}")
                    }
                }
            })
        }
    }

    /**
     * Registers properties annotated with {@code RocketNativeProperty} into the Lua table.
     *
     * @param table The LuaTable instance to register properties to.
     */
    private fun registerProperties(table: LuaTable) {
        val properties = this::class.memberProperties
            .mapNotNull { prop ->
                prop.findAnnotation<RocketNativeProperty>()?.let { annotation ->
                    val customName = annotation.name.takeIf { it != "INHERIT_FROM_DEFINITION" } ?: prop.name
                    customName to prop
                }
            }.toMap()

        val metatable = LuaTable()

        // Handle property getting
        metatable.set("__index", object : TwoArgFunction() {
            override fun call(self: LuaValue, key: LuaValue): LuaValue {
                val prop = properties[key.tojstring()] as? KProperty<*>
                    ?: return error("No property '${key.tojstring()}'")

                return try {
                    val value = prop.getter.call(this@RocketNative)
                    value.toLuaValue()
                } catch (e: Exception) {
                    error("Error getting '${prop.name}': ${e.message}")
                }
            }
        })

        // Handle property setting
        metatable.set("__newindex", object : ThreeArgFunction() {
            override fun call(self: LuaValue, key: LuaValue, value: LuaValue): LuaValue {
                val prop = properties[key.tojstring()] as? KMutableProperty<*>
                    ?: return error("No property '${key.tojstring()}'")

                return try {
                    prop.setter.call(this@RocketNative, value.toKotlinValue(prop.returnType.classifier))
                    TRUE
                } catch (e: Exception) {
                    error("Error setting '${prop.name}': ${e.message}")
                }
            }
        })

        table.setmetatable(metatable)
    }

    /**
     * Converts Lua arguments to Kotlin arguments based on function parameter types.
     *
     * @param func The function whose parameters should be converted.
     * @return An array of Kotlin compatible arguments.
     */
    private fun Varargs.toKotlinArgs(func: KFunction<*>): Array<Any?> {
        val params = func.parameters.drop(1) // Skip `this`
        return params.mapIndexed { index, param ->
            this.arg(index + 1).let { arg ->
                if (arg.istable()) {
                    arg.checktable().toClass(func)
                } else {
                    arg.toKotlinValue(param.type.classifier)
                }
            }
        }.toTypedArray()
    }

    /**
     * Converts a LuaValue to a Kotlin compatible value based on its type.
     *
     * @param type The expected Kotlin type.
     * @return The converted value in Kotlin.
     */
    private fun LuaValue.toKotlinValue(type: KClassifier?): Any? {
        return when (type) {
            String::class -> if (isnil()) null else tojstring()
            Boolean::class -> toboolean()
            Int::class -> toint()
            Double::class -> todouble()
            Float::class -> tofloat()
            Long::class -> tolong()
            else -> this
        }
    }

    /**
     * Converts a Kotlin value to a LuaValue.
     *
     * @return The corresponding LuaValue.
     */
    private fun Any?.toLuaValue(): LuaValue {
        return when (this) {
            is String -> LuaValue.valueOf(this)
            is Boolean -> LuaValue.valueOf(this)
            is Int -> LuaValue.valueOf(this)
            is Double -> LuaValue.valueOf(this)
            is Float -> LuaValue.valueOf(this.toDouble())
            is Long -> LuaValue.valueOf(this.toDouble())
            is RocketTable -> {
                val table = this.table
                set("__javaClass", TableSetOptions(getter = { LuaValue.valueOf(javaClass.name) }))
                table
            }
            is RocketLuaValue -> {
                throw RocketError("RocketLuaValue should not be used as a return type.")
            }
            is ArrayList<*> -> {
                val table = LuaTable()
                this.forEachIndexed { index, value ->
                    table.set(index + 1, value.toLuaValue())
                }
                table
            }
            is Enum<*> -> {
                val enumClass = this::class
                val enumTable = RocketEnum(enumClass.simpleName!!)
                enumTable.toLuaTable(this)
            }
            else -> {
                throw RocketError("Unsupported type: ${this?.javaClass?.simpleName ?: "null"}")
            }
        }
    }

    /**
     * Converts a LuaTable into a given class.
     */
    private fun LuaTable.toClass(func: KFunction<*>): Any { // XXX: stupid trick to also return enums.
        try {
            var className = get("__javaClass").tojstring()
            if (className == "nil") {
                var classes = func.parameters.map { it.type.classifier }
                classes = classes.drop(1)
                val tableProps = keys().asSequence().map { it.tojstring() }.toList()
                for (clazz in classes) {
                    val constructor = (clazz as KClass<*>).primaryConstructor
                    if (constructor != null) {
                        val constructorProps = constructor.parameters.map { it.name }
                        if (constructorProps.containsAll(tableProps)) {
                            className = clazz.qualifiedName!!
                            break
                        }
                    }
                }

            }
            val clazz = Class.forName(className).kotlin

            // if enum class
            if (clazz.java.isEnum) {
                val renum = RocketEnum(clazz.simpleName!!)
                return renum.fromLuaTable(this, clazz)
            } else {
                val constructor =
                    clazz.primaryConstructor
                        ?: throw IllegalArgumentException("No primary constructor found for $className")
                val args = constructor.parameters.map { param ->
                    val value = get(param.name)
                    value.toKotlinValue(param.type.classifier)
                }.toTypedArray()
                return constructor.call(*args) as RocketTable
            }
        } catch (e: Exception) {
            throw e
        }
    }
}