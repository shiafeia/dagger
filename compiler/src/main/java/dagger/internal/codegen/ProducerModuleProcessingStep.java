/*
 * Copyright (C) 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dagger.internal.codegen;

import com.google.auto.common.BasicAnnotationProcessor.ProcessingStep;
import com.google.auto.common.MoreElements;
import com.google.auto.common.SuperficialValidation;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import dagger.Binds;
import dagger.producers.ProducerModule;
import dagger.producers.Produces;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static javax.lang.model.util.ElementFilter.methodsIn;

/**
 * An annotation processor for generating Dagger implementation code based on the
 * {@link ProducerModule} (and {@link Produces}) annotation.
 *
 * @author Jesse Beder
 * @since 2.0
 */
final class ProducerModuleProcessingStep implements ProcessingStep {
  private final Messager messager;
  private final ModuleValidator moduleValidator;
  private final Validator<ExecutableElement> producesMethodValidator;
  private final Validator<ExecutableElement> bindsMethodValidator;
  private final ProductionBinding.Factory productionBindingFactory;
  private final ProducerFactoryGenerator factoryGenerator;
  private final Set<Element> processedModuleElements = Sets.newLinkedHashSet();

  ProducerModuleProcessingStep(
      Messager messager,
      ModuleValidator moduleValidator,
      Validator<ExecutableElement> producesMethodValidator,
      Validator<ExecutableElement> bindsMethodValidator,
      ProductionBinding.Factory productionBindingFactory,
      ProducerFactoryGenerator factoryGenerator) {
    this.messager = messager;
    this.moduleValidator = moduleValidator;
    this.producesMethodValidator = producesMethodValidator;
    this.bindsMethodValidator = bindsMethodValidator;
    this.productionBindingFactory = productionBindingFactory;
    this.factoryGenerator = factoryGenerator;
  }

  @Override
  public Set<Class<? extends Annotation>> annotations() {
    return ImmutableSet.of(Produces.class, ProducerModule.class);
  }

  @Override
  public Set<Element> process(
      SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
    // first, check and collect all produces methods
    ImmutableSet<ExecutableElement> validProducesMethods =
        producesMethodValidator.validate(
            messager, methodsIn(elementsByAnnotation.get(Produces.class)));

    // second, check and collect all bind methods
    ImmutableSet<ExecutableElement> validBindsMethods =
        bindsMethodValidator.validate(messager, methodsIn(elementsByAnnotation.get(Binds.class)));

    // process each module
    for (Element moduleElement :
        Sets.difference(elementsByAnnotation.get(ProducerModule.class),
            processedModuleElements)) {
      if (SuperficialValidation.validateElement(moduleElement)) {
        ValidationReport<TypeElement> report =
            moduleValidator.validate(MoreElements.asType(moduleElement));
        report.printMessagesTo(messager);

        if (report.isClean()) {
          ImmutableSet.Builder<ExecutableElement> moduleProducesMethodsBuilder =
              ImmutableSet.builder();
          ImmutableSet.Builder<ExecutableElement> moduleBindsMethodsBuilder =
              ImmutableSet.builder();
          List<ExecutableElement> moduleMethods =
              ElementFilter.methodsIn(moduleElement.getEnclosedElements());
          for (ExecutableElement methodElement : moduleMethods) {
            if (isAnnotationPresent(methodElement, Produces.class)) {
              moduleProducesMethodsBuilder.add(methodElement);
            }
            if (isAnnotationPresent(methodElement, Binds.class)) {
              moduleBindsMethodsBuilder.add(methodElement);
            }
          }
          ImmutableSet<ExecutableElement> moduleProducesMethods =
              moduleProducesMethodsBuilder.build();
          ImmutableSet<ExecutableElement> moduleBindsMethods = moduleBindsMethodsBuilder.build();

          if (Sets.difference(moduleProducesMethods, validProducesMethods).isEmpty()
              && Sets.difference(moduleBindsMethods, validBindsMethods).isEmpty()) {
            // all of the produces methods in this module are valid!
            // time to generate some factories!
            ImmutableSet<ProductionBinding> bindings =
                FluentIterable.from(moduleProducesMethods)
                    .transform(
                        new Function<ExecutableElement, ProductionBinding>() {
                          @Override
                          public ProductionBinding apply(ExecutableElement producesMethod) {
                            return productionBindingFactory.forProducesMethod(
                                producesMethod,
                                MoreElements.asType(producesMethod.getEnclosingElement()));
                          }
                        })
                    .toSet();

            try {
              for (ProductionBinding binding : bindings) {
                factoryGenerator.generate(binding);
              }
            } catch (SourceFileGenerationException e) {
              e.printMessageTo(messager);
            }
          }
        }

        processedModuleElements.add(moduleElement);
      }
    }
    return ImmutableSet.of();
  }
}
