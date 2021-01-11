package com.github.rodbate.blogcode.springframework.configurationclass;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

/**
 * @author rodbate
 * @since 2021/1/10
 */
@ComponentScan(basePackageClasses = Bootstrap.class)
public class Bootstrap {
    public static void main(String[] args) throws IllegalAccessException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Bootstrap.class);

        TestBean bean = context.getBean(TestBean.class);
        System.out.println(bean.getName());

        DemoConfiguration demoConfiguration = context.getBean(DemoConfiguration.class);
        System.out.println(demoConfiguration.getClass().getSimpleName());

        //$$beanFactory
        /*Field field = ReflectionUtils.findField(demoConfiguration.getClass(), "$$beanFactory");
        BeanFactory beanFactory = (BeanFactory)field.get(demoConfiguration);
        System.out.println(beanFactory == context.getDefaultListableBeanFactory());*/

        String strBean = context.getBean("stringBean", String.class);
        System.out.println(strBean);

        context.close();
    }
}
