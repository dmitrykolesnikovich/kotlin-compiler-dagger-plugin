package me.shika.test.dagger.renderer

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import me.shika.test.model.Binding
import me.shika.test.model.GraphNode
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.utils.addIfNotNull

class DaggerFactoryRenderer(private val componentBuilder: TypeSpec.Builder, private val componentName: ClassName) {
    fun getFactory(graphNode: GraphNode): PropertySpec? {
        val signature = graphNode.value.bindingType.source as? FunctionDescriptor
        val returnType = graphNode.value.key.type.typeName() ?: return null // FIXME report
        val providerType = returnType.provider()

        return componentBuilder.propertySpecs.find { it.type == providerType }.ifNull {
            componentBuilder.addFactory(graphNode, signature, returnType, providerType)
        }
    }

    private fun TypeSpec.Builder.addFactory(
        graphNode: GraphNode,
        signature: FunctionDescriptor?,
        returnType: TypeName,
        providerType: TypeName
    ): PropertySpec? {
        val parent = signature?.containingDeclaration as? ClassDescriptor
        val parentType = parent?.type() ?: graphNode.value.key.type.typeName()
        val name = graphNode.bindingName(parentType)

        val factoryType = componentName.nestedClass("${name}_Factory")
        val factoryMemberName = "${name}_Provider".decapitalize()
        val instanceProperty = graphNode.value.toProperty()

        val depsFactories = graphNode.dependencies.mapNotNull { getFactory(it) }
        val factoryProperties = depsFactories.toMutableList()
            .apply { addIfNotNull(instanceProperty) }

        addType(
            classWithFactories(factoryProperties, factoryType, providerType)
                .addFunction(
                    FunSpec.builder("get")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(returnType)
                        .addCode(graphNode.providerBody(instanceProperty, signature, depsFactories, parentType))
                        .build()
                )
                .build()
        )

        // Factory property in component (cached if scoped)
        val property = PropertySpec.builder(factoryMemberName, providerType)
            .factoryProperty(factoryType, depsFactories, parentType, graphNode.value.scopes.isNotEmpty())
            .build()

        addProperty(property)
        return property
    }

    private fun GraphNode.providerBody(
        instanceProperty: PropertySpec?,
        signature: FunctionDescriptor?,
        depsFactories: List<PropertySpec>,
        parentType: TypeName?
    ) = when (value.bindingType) {
            is Binding.Variation.StaticFunction -> {
                CodeBlock.of(
                    "return %T.${signature!!.name}(${depsFactories.joinToString(",") { "${it.name}.get()" }})",
                    parentType
                )
            }
            is Binding.Variation.Constructor -> {
                CodeBlock.of(
                    "return %T(${depsFactories.joinToString(",") { "${it.name}.get()" }})",
                    parentType
                )
            }
            is Binding.Variation.InstanceFunction -> {
                CodeBlock.of(
                    "return %N.${signature!!.name}(${depsFactories.joinToString(",") { "${it.name}.get()" }})",
                    instanceProperty!!.name
                )
            }
            is Binding.Variation.BoundInstance,
            is Binding.Variation.Component -> {
                CodeBlock.of(
                    "return %N", instanceProperty!!.name
                )
            }
        }

    private fun PropertySpec.Builder.factoryProperty(
        factoryType: TypeName,
        depsFactories: List<PropertySpec>,
        parentType: TypeName?,
        isScoped: Boolean
    ) = apply {
        val params = depsFactories.map { it.name }.toMutableList()
        componentBuilder.propertySpecs.find { it.type == parentType }?.let { params += it.name }
        if (parentType?.string() == componentName.string().removePrefix("Dagger")) { // hack
            params += "this"
        }

        val doubleCheckName = MemberName(
            ClassName("dagger.internal", "DoubleCheck"),
            "provider"
        )

        val args = hashMapOf(
            "doubleCheck" to doubleCheckName,
            "factoryType" to factoryType
        )

        initializer(
            CodeBlock.builder()
                .addNamed(
                    "%factoryType:T(${params.joinToString()})".letIf(isScoped) {
                        "%doubleCheck:M($it)"
                    },
                    args
                )
                .build()
        )
    }

    private fun TypeName.provider() =
        with(ParameterizedTypeName.Companion) {
            ClassName("javax.inject", "Provider")
                .parameterizedBy(this@provider)
        }

    private fun Binding.toProperty(): PropertySpec? {
        val type = when (bindingType) {
            is Binding.Variation.InstanceFunction -> (bindingType.source.containingDeclaration as ClassDescriptor).defaultType
            is Binding.Variation.StaticFunction,
            is Binding.Variation.Constructor -> null
            else -> key.type
        }?.typeName() ?: return null

        val name = type.name().decapitalize()

        return PropertySpec.builder(name, type, KModifier.PRIVATE)
            .initializer(name)
            .build()
    }

    private fun ClassDescriptor.type() = if (isCompanionObject) {
        (containingDeclaration as ClassDescriptor).defaultType.typeName()
    } else {
        defaultType.typeName()
    }

    private fun GraphNode.bindingName(parentType: TypeName?) = when (value.bindingType) {
        is Binding.Variation.StaticFunction,
        is Binding.Variation.InstanceFunction -> "${parentType?.name()}_${value.bindingType.source.name.asString().capitalize()}"
        is Binding.Variation.Constructor -> "${value.bindingType.source.constructedClass.name}"
        is Binding.Variation.BoundInstance -> "${value.bindingType.source.type.typeName()?.name()}"
        is Binding.Variation.Component -> "component_${value.bindingType.source.type()?.name()}"
    }
}