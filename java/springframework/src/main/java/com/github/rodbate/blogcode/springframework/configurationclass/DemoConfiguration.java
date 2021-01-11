package com.github.rodbate.blogcode.springframework.configurationclass;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author rodbate
 * @since 2021/1/10
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfiguration {

    @Bean
    public TestBean testBean() {
        TestBean testBean = new TestBean();
        testBean.setName("test-bean");
        System.out.println("testBean()");
        return testBean;
    }

    @Bean
    public String stringBean(TestBean testBean) {
        return "str-" + testBean.getName() + "-" + testBean.getBeanName();
    }
}
