package com.github.manosbatsis.kotlin.utils.kapt.dto.strategy.composition

import com.github.manosbatsis.kotlin.utils.ProcessingEnvironmentAware
import com.github.manosbatsis.kotlin.utils.api.DefaultValue
import com.github.manosbatsis.kotlin.utils.kapt.processor.AnnotatedElementInfo
import com.squareup.kotlinpoet.*
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.VariableElement

/** Simple implementation of [DtoMembersStrategy] */
open class SimpleDtoMembersStrategy<N : DtoNameStrategy, T : DtoTypeStrategy>(
        override val annotatedElementInfo: AnnotatedElementInfo,
        override val dtoNameStrategy: N,
        override val dtoTypeStrategy: T
) : DtoMembersStrategy, ProcessingEnvironmentAware, AnnotatedElementInfo by annotatedElementInfo {

    // Original type parameter, used in alt constructor and util functions
    val originalTypeParameter by lazy { ParameterSpec.builder("original", primaryTargetTypeElement.asKotlinTypeName()).build() }

    // Create DTO primary constructor
    val dtoConstructorBuilder = FunSpec.constructorBuilder()

    val dtoAltConstructorCodeBuilder = CodeBlock.builder().addStatement("")

    // Create patch function
    val patchFunctionBuilder by lazy { getToPatchedFunctionBuilder(originalTypeParameter) }
    val patchFunctionCodeBuilder by lazy {
        if (annotatedElementInfo.isNonDataClass)
            CodeBlock.builder().addStatement("val patched = %T(", primaryTargetTypeElement.asKotlinTypeName())
        else
            CodeBlock.builder().addStatement("val patched = original.copy(")
    }


    // Create mapping function
    val targetTypeFunctionBuilder by lazy { getToTargetTypeFunctionBuilder() }
    val targetTypeFunctionCodeBuilder by lazy {
        if (annotatedElementInfo.skipToTargetTypeFunction)
            CodeBlock.builder().addStatement("TODO(\"Not yet implemented\")")
        else
            CodeBlock.builder().addStatement("   return %T(",
                    dtoTypeStrategy.getDtoTarget().asType().asTypeName())
    }
    val companionObject by lazy { getCompanionBuilder() }

    val creatorFunctionBuilder by lazy { getCreatorFunctionBuilder(originalTypeParameter) }
    val creatorFunctionCodeBuilder by lazy {
        CodeBlock.builder().addStatement("return ${dtoNameStrategy.getClassName().simpleName}(")
    }


    override fun getCreatorFunctionBuilder(
            originalTypeParameter: ParameterSpec
    ): FunSpec.Builder {
        val creatorFunctionBuilder = FunSpec.builder("mapToDto")
                .addModifiers(KModifier.PUBLIC)
                .addKdoc(CodeBlock.builder()
                        .addStatement("Create a new DTO instance using the given [%T] as source.",
                                primaryTargetTypeElement.asType().asTypeName())
                        .build())
                .addParameter(originalTypeParameter)
                .returns(dtoNameStrategy.getClassName())
        return creatorFunctionBuilder
    }

    override fun getCompanionBuilder(): TypeSpec.Builder {
        return TypeSpec.companionObjectBuilder()
    }

    override fun addPropertyAnnotations(propertySpecBuilder: PropertySpec.Builder, variableElement: VariableElement) {
        propertySpecBuilder.copyAnnotationsByBasePackage(variableElement, copyAnnotationPackages, AnnotationSpec.UseSiteTarget.FIELD)
    }

    override fun toPropertyName(variableElement: VariableElement): String =
            variableElement.simpleName.toString()

    override fun toPropertyTypeName(variableElement: VariableElement): TypeName =
            variableElement.asKotlinTypeName().copy(nullable = true)

    override fun toDefaultValueExpression(variableElement: VariableElement): String {
        val mixinVariableElement = annotatedElementInfo
                .mixinTypeElementFields
                .find { it.simpleName == variableElement.simpleName }

        return listOfNotNull(mixinVariableElement, variableElement)
                .mapNotNull { findDefaultValueAnnotationValue(it) }
                .firstOrNull() ?: "null"
    }

    fun findDefaultValueAnnotationValue(variableElement: VariableElement): String? =
            variableElement.findAnnotationValue(DefaultValue::class.java, "value")?.value?.toString()

    override fun toTargetTypeStatement(fieldIndex: Int, variableElement: VariableElement, commaOrEmpty: String): DtoMembersStrategy.Statement? {
        val propertyName = toPropertyName(variableElement)
        return DtoMembersStrategy.Statement("      $propertyName = this.$propertyName${if (variableElement.isNullable()) "" else "?:errNull(\"$propertyName\")"}$commaOrEmpty")
    }

    override fun toPatchStatement(fieldIndex: Int, variableElement: VariableElement, commaOrEmpty: String): DtoMembersStrategy.Statement? {
        val propertyName = toPropertyName(variableElement)
        return DtoMembersStrategy.Statement("      $propertyName = this.$propertyName ?: original.$propertyName$commaOrEmpty")
    }

    override fun toAltConstructorStatement(
            fieldIndex: Int, variableElement: VariableElement, propertyName: String, propertyType: TypeName, commaOrEmpty: String
    ): DtoMembersStrategy.Statement? {
        return DtoMembersStrategy.Statement("      $propertyName = original.$propertyName$commaOrEmpty")
    }

    override fun toCreatorStatement(
            fieldIndex: Int,
            variableElement: VariableElement,
            propertyName: String,
            propertyType: TypeName,
            commaOrEmpty: String
    ): DtoMembersStrategy.Statement? {
        return DtoMembersStrategy.Statement("      $propertyName = original.$propertyName$commaOrEmpty")
    }

    override fun processDtoOnlyFields(
            typeSpecBuilder: TypeSpec.Builder,
            fields: List<VariableElement>
    ) {
        fields.forEachIndexed { fieldIndex, originalProperty ->
            val (propertyName, propertyType) =
                addProperty(originalProperty, fieldIndex, typeSpecBuilder)
            fieldProcessed(fieldIndex, originalProperty, propertyName, propertyType)
        }
    }

    override fun processFields(
            typeSpecBuilder: TypeSpec.Builder,
            fields: List<VariableElement>
    ) {
        fields.forEachIndexed { fieldIndex, originalProperty ->
            val commaOrEmpty = if (fieldIndex + 1 < fields.size) "," else ""
            // Tell KotlinPoet that the property is initialized via the constructor parameter,
            // by creating both a constructor param and member property
            val (propertyName, propertyType) = addProperty(originalProperty, fieldIndex, typeSpecBuilder)
            // TODO: just separate and decouple the darn component builders already
            // Add line to patch function
            patchFunctionCodeBuilder.addStatement(toPatchStatement(fieldIndex, originalProperty, commaOrEmpty))
            // Add line to map function
            if (!annotatedElementInfo.skipToTargetTypeFunction) targetTypeFunctionCodeBuilder.addStatement(
                    toTargetTypeStatement(
                            fieldIndex,
                            originalProperty,
                            commaOrEmpty))
            // Add line to alt constructor
            dtoAltConstructorCodeBuilder.addStatement(
                    toAltConstructorStatement(
                            fieldIndex,
                            originalProperty,
                            propertyName,
                            propertyType,
                            commaOrEmpty
                    )
            )
            // Add line to create
            creatorFunctionCodeBuilder.addStatement(
                toCreatorStatement(
                    fieldIndex,
                    originalProperty,
                    propertyName,
                    propertyType,
                    commaOrEmpty
                )
            )
            //
            fieldProcessed(fieldIndex, originalProperty, propertyName, propertyType)
        }
    }

    protected fun addProperty(
            originalProperty: VariableElement,
            fieldIndex: Int,
            typeSpecBuilder: TypeSpec.Builder
    ): Pair<String, TypeName> {
        val propertyName = toPropertyName(originalProperty)
        val propertyType = toPropertyTypeName(originalProperty)
        val propertyDefaultValue = toDefaultValueExpression(originalProperty)
        dtoConstructorBuilder.addParameter(
            ParameterSpec.builder(propertyName, propertyType)
                .defaultValue(propertyDefaultValue)
                .build()
        )
        val propertySpecBuilder = toPropertySpecBuilder(fieldIndex, originalProperty, propertyName, propertyType)
        addPropertyAnnotations(propertySpecBuilder, originalProperty)
        typeSpecBuilder.addProperty(propertySpecBuilder.build())
        return Pair(propertyName, propertyType)
    }

    /** Override to add additional functionality to your [DtoMembersStrategy] implementation */
    override fun fieldProcessed(
            fieldIndex: Int,
            originalProperty: VariableElement,
            propertyName: String,
            propertyType: TypeName
    ) {
        // NO-OP
    }

    override fun toPropertySpecBuilder(
            fieldIndex: Int, variableElement: VariableElement, propertyName: String, propertyType: TypeName
    ): PropertySpec.Builder = PropertySpec.builder(propertyName, propertyType)
            .mutable()
            .addModifiers(KModifier.PUBLIC)
                .initializer(propertyName)


    // Create DTO alternative constructor
    override fun getAltConstructorBuilder() = FunSpec.constructorBuilder().addParameter(originalTypeParameter)
            .addKdoc(CodeBlock.builder()
                    .addStatement("Alternative constructor, used to map ")
                    .addStatement("from the given [%T] instance.", primaryTargetTypeElement.asKotlinTypeName()).build())

    override fun getToPatchedFunctionBuilder(
            originalTypeParameter: ParameterSpec
    ): FunSpec.Builder {
        val patchFunctionBuilder = FunSpec.builder("toPatched")
                .addModifiers(KModifier.OVERRIDE)
                .addKdoc(CodeBlock.builder()
                        .addStatement("Create a patched copy of the given [%T] instance,", primaryTargetTypeElement.asKotlinTypeName())
                        .addStatement("updated using this DTO's non-null properties.").build())
                .addParameter(originalTypeParameter)
                .returns(primaryTargetTypeElement.asKotlinTypeName())
        return patchFunctionBuilder
    }

    override fun getToTargetTypeFunctionBuilder(): FunSpec.Builder {
        val toStateFunctionBuilder = FunSpec.builder("toTargetType")
                .addModifiers(KModifier.OVERRIDE)
                .addKdoc(if (annotatedElementInfo.skipToTargetTypeFunction)
                    CodeBlock.builder().addStatement("Not yet implemented").build()
                else CodeBlock.builder()
                        .addStatement("Create an instance of [%T], using this DTO's properties.", primaryTargetTypeElement.asKotlinTypeName())
                        .addStatement("May throw a [DtoInsufficientStateMappingException] ")
                        .addStatement("if there is mot enough information to do so.").build())
                .returns(primaryTargetTypeElement.asKotlinTypeName())
        return toStateFunctionBuilder
    }

    override fun finalize(typeSpecBuilder: TypeSpec.Builder) {
        // Complete alt constructor
        val dtoAltConstructorBuilder = getAltConstructorBuilder()
            .callThisConstructor(dtoAltConstructorCodeBuilder.build())
        addAltConstructor(typeSpecBuilder, dtoAltConstructorBuilder)
        // Complete creator function

        // Complete creator function
        creatorFunctionCodeBuilder.addStatement(")")
        //creatorFunctionCodeBuilder.addStatement("return dto")
        // Complete patch function
        patchFunctionCodeBuilder.addStatement(")")
        patchFunctionCodeBuilder.addStatement("return patched")
        // Complete mapping function
        if (!annotatedElementInfo.skipToTargetTypeFunction)
            targetTypeFunctionCodeBuilder.addStatement("   )")
        // Add functions
        typeSpecBuilder
                .primaryConstructor(dtoConstructorBuilder.build())
                .addType(companionObject.addFunction(
                        creatorFunctionBuilder
                                .addStatement(creatorFunctionCodeBuilder.build().toString())
                                .build()
                ).build())
        typeSpecBuilder.addFunction(patchFunctionBuilder.addCode(patchFunctionCodeBuilder.build()).build())
        typeSpecBuilder.addFunction(targetTypeFunctionBuilder.addCode(targetTypeFunctionCodeBuilder.build()).build())
    }

    override fun addAltConstructor(typeSpecBuilder: TypeSpec.Builder, dtoAltConstructorBuilder: FunSpec.Builder) {
        typeSpecBuilder.addFunction(dtoAltConstructorBuilder.build())
    }

    override val processingEnvironment: ProcessingEnvironment
        get() = annotatedElementInfo.processingEnvironment
}