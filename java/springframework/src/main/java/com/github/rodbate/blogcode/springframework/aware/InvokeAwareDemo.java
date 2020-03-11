package com.github.rodbate.blogcode.springframework.aware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.context.*;
import org.springframework.context.annotation.ImportAware;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.instrument.classloading.LoadTimeWeaver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringValueResolver;

/**
 * @author rodbate
 * @since 2020/3/11
 */
@Component
public class InvokeAwareDemo
    implements BeanNameAware, BeanClassLoaderAware, BeanFactoryAware, EnvironmentAware, EmbeddedValueResolverAware,
    ResourceLoaderAware, ApplicationEventPublisherAware, MessageSourceAware, ApplicationContextAware, ImportAware,
    LoadTimeWeaverAware {

    private final static Logger LOG = LoggerFactory.getLogger(InvokeAwareDemo.class);

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        LOG.info(" ------ BeanClassLoaderAware::setBeanClassLoader invoked");
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        LOG.info(" ------ BeanFactoryAware::setBeanFactory invoked");
    }

    @Override
    public void setBeanName(String s) {
        LOG.info(" ------ BeanNameAware::setBeanName invoked");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        LOG.info(" ------ ApplicationContextAware::setApplicationContext invoked");
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        LOG.info(" ------ ApplicationEventPublisherAware::setApplicationEventPublisher invoked");
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        LOG.info(" ------ EmbeddedValueResolverAware::setEmbeddedValueResolver invoked");
    }

    @Override
    public void setEnvironment(Environment environment) {
        LOG.info(" ------ EnvironmentAware::setEnvironment invoked");
    }

    @Override
    public void setMessageSource(MessageSource messageSource) {
        LOG.info(" ------ MessageSourceAware::setMessageSource invoked");
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        LOG.info(" ------ ResourceLoaderAware::setResourceLoader invoked");
    }

    @Override
    public void setImportMetadata(AnnotationMetadata importMetadata) {
        LOG.info(" ------ ImportAware::setImportMetadata invoked");
    }

    @Override
    public void setLoadTimeWeaver(LoadTimeWeaver loadTimeWeaver) {
        LOG.info(" ------ LoadTimeWeaverAware::setLoadTimeWeaver invoked");
    }
}
