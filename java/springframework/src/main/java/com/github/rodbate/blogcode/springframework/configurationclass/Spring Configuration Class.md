## Spring Configuration Class

本文主要是聊一聊Spring 5.2引入的针对`Configuration Class`的优化及目的



### 前言

自从Spring 3.0 引入`@Configuration` 这个配置类注解, 那就意味着Spring的XML配置文件的方式已经过去了，迎来了一个注解(java class)的崭新时代。因此Spring后面版本大量使用`Configuration Class`, 而我们的工作学习中只要涉及到了Spring，这个`Configuration Class`的大量使用也是不可避免的，所有大家对这个也是非常常见的。看一下`@Configuration`这个注解

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Configuration {

	@AliasFor(annotation = Component.class)
	String value() default "";
	
    //5.2引入的feature
	boolean proxyBeanMethods() default true;

}
```

> 1. Configuration也是使用了Comonent元注解(适用于ComponentScan机制)
> 2. proxyBeanMethods 5.2引入的优化功能,默认为Configuraion Class生成代理类(兼容老版本), 推荐使用不走代理的Configuration Class(proxyBeanMethods = false)



### Configuraion Class为什么要走代理？

来看看下面的一个使用案例

```java
@Configuration
public class DemoConfiguration {
  	
    @Bean
    public BeanA beanA() {
        return new BeanA();
    }
    
    @Bean
    public BeanB beanB() {
        return new BeanB(beanA());
    }
}
```

> 可以看到BeanB的初始化使用到了beanA, 这种情况是经常能遇到的。Spring为了支持这种bean方法的直接引用，因此在老版本中强制将Configuraiton Class进行代理这种bean methods(这里指的是beanA()方法)，来由Spring框架自己来处理【直接从底层容器(BeanFactory)来查找对应Bean】。
>
> 
>
> 如果不加这层代理？
>
> 造成的后果就是我想依赖的bean并不是由Spring容器(BeanFactory)来进行管理的(那查找,依赖注入,自动代理,Spring Bean生命周期管理使用等等Spring提供的功能都无法使用了)，这种情况并不是我想要的。因此Spring才加了这层代理。



### Configuration Class最佳使用方式(5.2以后)

可以将上面的使用方式改造一下

```java
@Configuration(proxyBeanMethods = false)
public class DemoConfiguration {
  	
    @Bean
    public BeanA beanA() {
        return new BeanA();
    }
    
    @Bean
    public BeanB beanB(BeanA beanA) {
        return new BeanB(beanA);
    }
}
```

> 官方推荐的方式就是以下两点:
>
> 1. 去除Configuration Class自动代理 - proxyBeanMethods = false
> 2. 需要bean引用的使用依赖注入的方式(DI)



**去除没必要代理的好处**

+ 提高Spring容器的启动效率
+ 减少字节码内存占用损耗(代理都会新生成一份字节码)



好了，简单地梳理完了来龙去脉和使用方式，下面也可以来看看Spring是如何实现`Configuration Class` bean方法代理的，不感兴趣的就可以跳过了。



### Configuration Class代理原理

我们都知道Spring对于这种类的代理都是采用的`CGLIB`，对于`Configuraion Class`的代理也不例外。

为了节省篇幅我们直接切到`ConfigurationClassEnhancer` `Configuration Class`的代理生成器。

> 入口：Configuration Class统一处理器ConfigurationClassPostProcessor



看下CGLIB Enchancer配置的核心代码:

```java
private Enhancer newEnhancer(Class<?> configSuperClass, @Nullable ClassLoader classLoader) {
    Enhancer enhancer = new Enhancer();
    enhancer.setSuperclass(configSuperClass);
    enhancer.setInterfaces(new Class<?>[] {EnhancedConfiguration.class});
    enhancer.setUseFactory(false);
    enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
    enhancer.setStrategy(new BeanFactoryAwareGeneratorStrategy(classLoader));
    enhancer.setCallbackFilter(CALLBACK_FILTER);
    enhancer.setCallbackTypes(CALLBACK_FILTER.getCallbackTypes());
    return enhancer;
}
```

1. `enhancer.setSuperclass(configSuperClass)` 将目标配置类设置为父类(`DemoConfiguration`)

2. `enhancer.setInterfaces(new Class<?>[] {EnhancedConfiguration.class})`设置接口`EnhancedConfiguration`

   ```java
   public interface EnhancedConfiguration extends BeanFactoryAware {
   }
   ```

   这个接口有两个用处：一是起到标记接口的作用(比如可以通过这个接口来判断目标类是不是代理类`EnhancedConfiguration.class.isAssignableFrom(targetClass)`); 而是为代理类实现`BeanFactoryAware`接口，起到了底层容器`BeanFactory`的获取作用

3. 接下来的配置都是方法拦截器以及自定义代码生成器的作用，下面再来详细分析



#### 设置代理类NamingPolicy

```java
enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE)
```

Spring所有通过CGLIB代理生成的类都是采用`SpringNamingPolicy`命名规则, 些微看下(没什么好看的)

```java
public class SpringNamingPolicy extends DefaultNamingPolicy {
	//...

	@Override
	protected String getTag() {
		return "BySpringCGLIB";
	}

}

//Spring从CGLIB拷过来修改包名
public class DefaultNamingPolicy implements NamingPolicy {
	//...
    public String getClassName(String prefix, String source, Object key, Predicate names){
        if (prefix == null) {
            prefix = "org.springframework.cglib.empty.Object";
        } else if (prefix.startsWith("java")) {
            prefix = "$" + prefix;
        }
        String base =
            prefix + "$$" + 
            source.substring(source.lastIndexOf('.') + 1) +
            getTag() + "$$" +
            Integer.toHexString(STRESS_HASH_CODE ? 0 : key.hashCode());
        String attempt = base;
        int index = 2;
        while (names.evaluate(attempt))
            attempt = base + "_" + index++;
        return attempt;
    }

    protected String getTag() {
        return "ByCGLIB";
    }
    //...
}
```

> 拓展： Spring改CGLIB包名
>
> ```
> //spring-core.gradle
> task cglibRepackJar(type: ShadowJar) {
>    archiveBaseName = 'spring-cglib-repack'
>    archiveVersion = cglibVersion
>    configurations = [project.configurations.cglib]
>    relocate 'net.sf.cglib', 'org.springframework.cglib' //这里
>    relocate 'org.objectweb.asm', 'org.springframework.asm'
> }
> ```



> 从上面的就能看出来代理类生成规则(复杂情况就没必要分析了)：
>
> 比如 -> com.demo.DemoConfiguration
>
> 生成代理类名称 -> com.demo.DemoConfiguration$$EnhancerBySpringCGLIB$$666888



#### 生成底层容器BeanFactory字段

```java
enhancer.setStrategy(new BeanFactoryAwareGeneratorStrategy(classLoader))
```

```java
private static class BeanFactoryAwareGeneratorStrategy extends
			ClassLoaderAwareGeneratorStrategy {
	...
    @Override
    protected ClassGenerator transform(ClassGenerator cg) throws Exception {
        ClassEmitterTransformer transformer = new ClassEmitterTransformer() {
            @Override
            public void end_class() {
                declare_field(Constants.ACC_PUBLIC, BEAN_FACTORY_FIELD, Type.getType(BeanFactory.class), null);
                super.end_class();
            }
        };
        return new TransformingClassGenerator(cg, transformer);
    }

}
```

```java
declare_field(Constants.ACC_PUBLIC, BEAN_FACTORY_FIELD, Type.getType(BeanFactory.class), null)
```

> 为代理类生成了一个public的BeanFactory实例字段



#### BeanFactory字段设置代理方法

`BeanFactoryAwareMethodInterceptor`

过滤匹配`BeanFactoryAware#setBeanFactory`方法，为其进行方法代理

```java
public static boolean isSetBeanFactory(Method candidateMethod) {
    return (candidateMethod.getName().equals("setBeanFactory") &&
            candidateMethod.getParameterCount() == 1 &&
            BeanFactory.class == candidateMethod.getParameterTypes()[0] &&
            BeanFactoryAware.class.isAssignableFrom(candidateMethod.getDeclaringClass()));
}
```



核心方法代理逻辑

```java
Field field = ReflectionUtils.findField(obj.getClass(), BEAN_FACTORY_FIELD);
Assert.state(field != null, "Unable to find generated BeanFactory field");
field.set(obj, args[0]);
```

就是把Spring 底层容器BeanFactory设置给代理类自动生成的实例字段`$$beanFactory`





#### 代理Bean Methods

核心代码逻辑`BeanMethodInterceptor#resolveBeanReference`



从代理类中取出`$$beanFactory字段`

```java
ConfigurableBeanFactory beanFactory = getBeanFactory(enhancedConfigInstance);
```



计算出引用bean方法的bean名称

```java
String beanName = BeanAnnotationHelper.determineBeanNameFor(beanMethod);
```



跳过Spring内部`@Bean`的生成逻辑(核心就是BeanDefinition的Factory Method -> 调用目标beanMethod来创建bean实例)

```java
if (isCurrentlyInvokedFactoryMethod(beanMethod)) {
	return cglibMethodProxy.invokeSuper(enhancedConfigInstance, beanMethodArgs);
}
```

直接调用父类的对应方法来生成bean实例，即是`DemoConfiguration#beanA()` 或者 `DemoConfiguration#beanB()`



其它通过方法引用来获取bean的情况

通过beanName从beanFactory拿到对应的bean

```java
Object beanInstance = (useArgs ? beanFactory.getBean(beanName, beanMethodArgs) :
						beanFactory.getBean(beanName));
```



到这里`Configuration Class`基本的的脉络流程已经梳理完了，最后用伪代码来总结一下代理过程



### 代理过程总结

还是基于上面的`DemoConfiguration`来进行总结。

Spring为`DemoConfiguration`生成的代理类如下：

```java
public class DemoConfiguration$$EnhancerBySpringCGLIB$$666888 extends DemoConfiguration implements EnhancedConfiguration {
    //生成beanFactory字段
    public BeanFactory $$beanFactory;
    
    //代理setBeanFactory方法
    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
     	this.$$beanFactory = beanFactory;   
    }
    
    //代理bean methods, 方便起见下面就用一个方法来描述了
    @Override
    public BeanX beanMthods(args) {
        //如果是创建Spring bean的流程则直接调用父类方法
     	if (isCurrentlyInvokedFactoryMethod(beanMethod)) {
            return super.beanMethods(args);
        }
        
        //通过方法引用来获取对应的容器bean
        String beanName = determineBeanName();
        return this.$$beanFactory.getBean(beanName, args)
    }
    
    private String determineBeanName() {
        if(@Bean.getValue or @Bean.getName is not empty) {
            return value or name;
        }
        return methodName;
    }
}
```

简单的用上面的伪代码描述了一下`Configruation Class`的主体代理过程，到此结束。