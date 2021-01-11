package com.github.rodbate.blogcode.springframework.configurationclass;

import org.springframework.beans.factory.BeanNameAware;

/**
 * @author rodbate
 * @since 2021/1/10
 */
public class TestBean implements BeanNameAware {
    private String name;
    private String beanName;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBeanName() {
        return beanName;
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }
}
