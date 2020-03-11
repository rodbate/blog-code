package com.github.rodbate.blogcode.springframework.aware;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * @author rodbate
 * @since 2020/3/11
 */
@Import(InvokeAwareDemo.class)
@ComponentScan(basePackageClasses = BootstrapMain.class)
public class BootstrapMain {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(BootstrapMain.class);

        ctx.close();
    }
}
