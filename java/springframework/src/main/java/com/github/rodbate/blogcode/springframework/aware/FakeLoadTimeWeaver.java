package com.github.rodbate.blogcode.springframework.aware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.instrument.classloading.LoadTimeWeaver;
import org.springframework.stereotype.Component;

import java.lang.instrument.ClassFileTransformer;

/**
 * @author rodbate
 * @since 2020/3/11
 */
@Component(ConfigurableApplicationContext.LOAD_TIME_WEAVER_BEAN_NAME)
public class FakeLoadTimeWeaver implements LoadTimeWeaver {
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public void addTransformer(ClassFileTransformer transformer) {
        log.info("FakeLoadTimeWeaver#addTransformer");
    }

    @Override
    public ClassLoader getInstrumentableClassLoader() {
        log.info("FakeLoadTimeWeaver#getInstrumentableClassLoader");
        return null;
    }

    @Override
    public ClassLoader getThrowawayClassLoader() {
        log.info("FakeLoadTimeWeaver#getThrowawayClassLoader");
        return null;
    }
}
