package me.shika.di.dagger.renderer

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import me.shika.di.dagger.renderer.creator.DaggerBuilderRenderer
import me.shika.di.dagger.renderer.dsl.overrideFunction
import me.shika.di.dagger.renderer.dsl.primaryConstructor
import me.shika.di.dagger.resolver.DaggerComponentDescriptor
import me.shika.di.dagger.resolver.creator.DaggerBuilderDescriptor
import me.shika.di.dagger.resolver.creator.DaggerFactoryDescriptor
import me.shika.di.model.Endpoint
import me.shika.di.model.ResolveResult
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.renderer.render
import java.io.File

class DaggerComponentRenderer(
    private val componentDescriptor: DaggerComponentDescriptor
) {
    private val definition = componentDescriptor.definition
    private val componentClassName = ClassName(
        componentDescriptor.file.packageFqName.render(),
        "Dagger${definition.name}"
    )

    fun render(sourcesDir: File) {
        val fileSpec = FileSpec.builder(componentClassName.packageName, componentClassName.simpleName)
            .addType(renderComponent(componentDescriptor.graph))
            .build()

        fileSpec.writeTo(sourcesDir)
    }

    private fun renderComponent(results: List<ResolveResult>): TypeSpec =
        TypeSpec.classBuilder(componentClassName)
            .apply {
                if (definition.visibility == Visibilities.INTERNAL) {
                    addModifiers(INTERNAL)
                }
                extendComponent()
                creator()
                addDependencyInstances()
                addBindings(results)
            }
            .build()

    private fun TypeSpec.Builder.extendComponent() = apply {
        if (definition.kind == ClassKind.INTERFACE) {
            addSuperinterface(definition.defaultType.typeName()!!)
        } else {
            superclass(definition.defaultType.typeName()!!)
        }
    }

    private fun TypeSpec.Builder.creator() = apply {
        when (val descriptor = componentDescriptor.creatorDescriptor) {
            is DaggerFactoryDescriptor -> me.shika.di.dagger.renderer.creator.DaggerFactoryRenderer(
                componentClassName,
                componentDescriptor.parameters,
                this
            ).render(descriptor)
            is DaggerBuilderDescriptor -> DaggerBuilderRenderer(
                componentClassName,
                componentDescriptor.parameters,
                this
            ).render(descriptor)
        }
    }

    private fun TypeSpec.Builder.addDependencyInstances() {
        val properties = componentDescriptor.parameters.map {
            val name = it.parameterName()
            PropertySpec.builder(name, it.type.typeName()!!, PRIVATE)
                .initializer(name)
                .build()
        }

        addProperties(properties)
        primaryConstructor {
            addModifiers(PRIVATE)
            addParameters(properties.map { it.toParameter() })
        }
    }

    private fun TypeSpec.Builder.addBindings(results: List<ResolveResult>) = apply {
        val factoryRenderer = DaggerFactoryRenderer(this, componentClassName)
        val membersInjectorRenderer =
            DaggerMembersInjectorRenderer(this, componentClassName, factoryRenderer)
        results.groupBy { it.endpoint.source }
            .map { (_, results) ->
                val result = results.first()
                when (val endpoint = result.endpoint) {
                    is Endpoint.Provided -> {
                        val factory = factoryRenderer.getFactory(result.graph.single())
                        overrideFunction(endpoint.source) {
                            addCode("return %N.get()", factory)
                        }
                    }
                    is Endpoint.Injected -> {
                        overrideFunction(endpoint.source) {
                            val injectedValue = parameters.first()
                            val membersInjector = membersInjectorRenderer.getMembersInjector(injectedValue.type, results)
                            addCode("%N.injectMembers(${injectedValue.name})", membersInjector)
                        }
                    }
                }
            }
    }
}

internal fun TypeName.name() = asString()