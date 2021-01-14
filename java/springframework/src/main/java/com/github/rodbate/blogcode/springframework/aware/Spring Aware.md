## Spring Aware接口执行时机源码深度解读

Spring中有这样一种标记接口的存在Aware,只要是spring管理的bean实现了Aware接口，那么spring就会在bean创建的某个时机将相应的资源注入到该spring bean中，比如ApplicationContextAware, 会将spring 应用上下文ApplicationContext自动注入到bean中。

```java
//Spring Aware标记接口
public interface Aware {

}
```



### Spring内建的Aware接口

Spring 内建的Aware大概分为以下几种



#### Spring core 和 context的内建Aware接口

+ ApplicationEventPublisherAware
+ MessageSourceAware
+ ResourceLoaderAware
+ BeanFactoryAware
+ EnvironmentAware
+ EmbeddedValueResolverAware
+ ImportAware
+ LoadTimeWeaverAware
+ BeanClassLoaderAware
+ BeanNameAware
+ ApplicationContextAware



#### Spring web内建的Aware接口

+ ServletContextAware
+ ServletConfigAware



#### Spring其它内建Aware接口

+ SchedulerContextAware (spring scheduling)
+ NotificationPublisherAware  (spring jmx export)
+ BootstrapContextAware (spring jca)



如此多的Aware接口，还不包含定制以及第三方引用的(关于如何定制自己的Aware接口不在此探讨，会专门拿出一章来深究), 那么它们的执行时机和顺序是怎样的呢？在我们使用到多个Aware接口而且还需要注意其执行时机和顺序的时候，就必须要弄清楚这一块的逻辑。接下来就是本文重点，深入spring源码来考证探究其原理及发现总结其执行时机以及执行顺序的结论。





### Spring内建Aware接口的执行时机及顺序

我们可以猜想到Aware接口的执行时机肯定是在Spring Bean创建的时候，那究竟具体在哪呢？接下来一起来探究一下。

纵观Spring，对于Aware接口的执行实现主要有一下两种模式

+ 初始化Bean的时候直接进行方法调用 -> setXXXX
+ BeanPostProcessor -> Object postProcessBeforeInitialization(Object bean, String beanName)



**结论：直接方法调用的时机要早于通过BeanPostProcessor#postProcessBeforeInitialization调用的时机**

接下来分析一下：

org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory

```java
protected Object initializeBean(final String beanName, final Object bean, @Nullable RootBeanDefinition mbd) {
  if (System.getSecurityManager() != null) {
    AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
      invokeAwareMethods(beanName, bean);
      return null;
    }, getAccessControlContext());
  }
  else {
    invokeAwareMethods(beanName, bean);
  }

  Object wrappedBean = bean;
  if (mbd == null || !mbd.isSynthetic()) {
    wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
  }

  try {
    invokeInitMethods(beanName, wrappedBean, mbd);
  }
  catch (Throwable ex) {
    throw new BeanCreationException(
      (mbd != null ? mbd.getResourceDescription() : null),
      beanName, "Invocation of init method failed", ex);
  }
  if (mbd == null || !mbd.isSynthetic()) {
    wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
  }

  return wrappedBean;
}
```



由以上代码片段可以看出，在Spring初始化bean的阶段，首先是先调用执行`invokeAwareMethods(beanName, bean)`, 然后再执行`BeanPostProcessor#postProcessBeforeInitialization`, 因此Aware接口执行顺序是 `直接方法调用 > 通过BeanPostProcessor#postProcessBeforeInitialization执行调用`。

下面来具体分析一下具体Aware接口的调用执行顺序。



#### 直接方法调用 -> invokeAwareMethods(beanName, bean)

```java
private void invokeAwareMethods(final String beanName, final Object bean) {
  if (bean instanceof Aware) {
    if (bean instanceof BeanNameAware) {
      ((BeanNameAware) bean).setBeanName(beanName);
    }
    if (bean instanceof BeanClassLoaderAware) {
      ClassLoader bcl = getBeanClassLoader();
      if (bcl != null) {
        ((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
      }
    }
    if (bean instanceof BeanFactoryAware) {
      ((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
    }
  }
}
```



**由以上代码片段可以得出结论：Aware接口最先执行顺序是 `BeanNameAware -> BeanClassLoaderAware -> BeanFactoryAware`**



可以写段小代码验证一下。

```java
@Component
public class InvokeAwareDemo implements BeanNameAware, BeanClassLoaderAware, BeanFactoryAware {

    private final static Logger LOG = LoggerFactory.getLogger(InvokeAwareMethodsDemo.class);

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
}
```

运行结果如下：

```
  ------ BeanNameAware::setBeanName invoked
  ------ BeanClassLoaderAware::setBeanClassLoader invoked
  ------ BeanFactoryAware::setBeanFactory invoked
```

因此可以验证上面的结论。





#### BeanPostProcessor#postProcessBeforeInitialization

第二种模式是通过BeanPostProcessor#postProcessBeforeInitialization的方式来调用相应的Aware接口

核心代码片段如下所示：

org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory

```java
protected Object initializeBean(final String beanName, final Object bean, @Nullable RootBeanDefinition mbd) {
  //....其余代码省略

  Object wrappedBean = bean;
  if (mbd == null || !mbd.isSynthetic()) {
    wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
  }

  //....其余代码省略
}
```

可以看到通过applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName)来执行相应的Aware接口的BeanPostProcessor, 那么多个Aware接口的执行顺序就取决于相应的BeanPostProcessor的执行顺序。



其中一个Spring内置的核心Aware BeanPostProcessor是`ApplicationContextAwareProcessor`, 可以看看这个processor关联的Aware接口

```java
public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
  if (!(bean instanceof EnvironmentAware || bean instanceof EmbeddedValueResolverAware ||
        bean instanceof ResourceLoaderAware || bean instanceof ApplicationEventPublisherAware ||
        bean instanceof MessageSourceAware || bean instanceof ApplicationContextAware)){
    return bean;
  }

  AccessControlContext acc = null;

  if (System.getSecurityManager() != null) {
    acc = this.applicationContext.getBeanFactory().getAccessControlContext();
  }

  //invokeAwareInterfaces(bean)调用相关联的Aware接口
  if (acc != null) {
    AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
      invokeAwareInterfaces(bean);
      return null;
    }, acc);
  }
  else {
    invokeAwareInterfaces(bean);
  }

  return bean;
}
```



`invokeAwareInterfaces(bean);`

```java
private void invokeAwareInterfaces(Object bean) {
  if (bean instanceof EnvironmentAware) {
    ((EnvironmentAware) bean).setEnvironment(this.applicationContext.getEnvironment());
  }
  if (bean instanceof EmbeddedValueResolverAware) {
    ((EmbeddedValueResolverAware) bean).setEmbeddedValueResolver(this.embeddedValueResolver);
  }
  if (bean instanceof ResourceLoaderAware) {
    ((ResourceLoaderAware) bean).setResourceLoader(this.applicationContext);
  }
  if (bean instanceof ApplicationEventPublisherAware) {
    ((ApplicationEventPublisherAware) bean).setApplicationEventPublisher(this.applicationContext);
  }
  if (bean instanceof MessageSourceAware) {
    ((MessageSourceAware) bean).setMessageSource(this.applicationContext);
  }
  if (bean instanceof ApplicationContextAware) {
    ((ApplicationContextAware) bean).setApplicationContext(this.applicationContext);
  }
}
```

从以上代码可知`ApplicationContextAwareProcessor`关联了大部分Spring内置Aware接口,它们的执行顺序如下：

> EnvironmentAware -> EmbeddedValueResolverAware -> ResourceLoaderAware -> ApplicationEventPublisherAware -> MessageSourceAware -> ApplicationContextAware

验证代码示例由于篇幅原因就不贴了，文末会有代码地址，有需要可自行查看, 执行结果贴一下

```
  ------ BeanNameAware::setBeanName invoked
  ------ BeanClassLoaderAware::setBeanClassLoader invoked
  ------ BeanFactoryAware::setBeanFactory invoked
  ------ EnvironmentAware::setEnvironment invoked
  ------ EmbeddedValueResolverAware::setEmbeddedValueResolver invoked
  ------ ResourceLoaderAware::setResourceLoader invoked
  ------ ApplicationEventPublisherAware::setApplicationEventPublisher invoked
  ------ MessageSourceAware::setMessageSource invoked
  ------ ApplicationContextAware::setApplicationContext invoked
```



由于Aware的接口的调用受到BeanPostProcessor的直接影响，因此BeanPostProcessor的执行顺序也就是Aware接口的调用顺序。可以看看`ApplicationContextAwareProcessor`的设置执行时机。



`AbstractApplicationContext`代码片段

```java
public void refresh() throws BeansException, IllegalStateException {
		synchronized (this.startupShutdownMonitor) {
			// Prepare this context for refreshing.
			prepareRefresh();

			// Tell the subclass to refresh the internal bean factory.
			ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

			// Prepare the bean factory for use in this context.
			prepareBeanFactory(beanFactory);
      
      //....省略其余代码
}

protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		//....省略其余代码
		// Configure the bean factory with context callbacks.
		beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));
  
  	//....省略其余代码
}
```



`beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this)`这里可以看到`ApplicationContextAwareProcessor`直接第一个加入到该BeanFactory中。



下面稍微看下`BeanPostProcessor`调用执行顺序(本文不作详细深究)



#### BeanPostProcessor调用执行顺序

回到上面的org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory#initializeBean代码片段中的

```java
//....其余代码省略
Object wrappedBean = bean;
if (mbd == null || !mbd.isSynthetic()) {
  wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
}
 //....其余代码省略

@Override
public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
  throws BeansException {

  Object result = existingBean;
  for (BeanPostProcessor processor : getBeanPostProcessors()) {
    Object current = processor.postProcessBeforeInitialization(result, beanName);
    if (current == null) {
      return result;
    }
    result = current;
  }
  return result;
}

public List<BeanPostProcessor> getBeanPostProcessors() {
  return this.beanPostProcessors;
}

private final List<BeanPostProcessor> beanPostProcessors = new CopyOnWriteArrayList<>();

```

由以上可知beanPostProcessors是关联该BeanFactory的有序列表, 这个列表的数据来源就是上文所提到的`ConfigurableBeanFactory#addBeanPostProcessor(BeanPostProcessor beanPostProcessor)` 这个方法。



回到`AbstractApplicationContext#refresh()`中的

```java
registerBeanPostProcessors(beanFactory);//向BeanFactory注入BeanPostProcessors
```

注册BeanPostProcessor的最终执行者是`PostProcessorRegistrationDelegate.registerBeanPostProcessors`

这其中的排序规则如下(针对于属于该BeanFactory的 BeanPostProcessor BeanDefinition): 

`String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);`

+ 实现了PriorityOrdered接口优先级最高, 再按order进行排序 小 -> 大
+ 其次是实现了Ordered接口, 再按order进行排序 小 -> 大
+ 其它的根据BeanDefinition Spring注册顺序来



当然还可以通过`BeanFactoryPostProcessor`来配置该BeanFactory, 举个例子`ConfigurationClassPostProcessor`

```java
@Override
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
  int factoryId = System.identityHashCode(beanFactory);
  if (this.factoriesPostProcessed.contains(factoryId)) {
    throw new IllegalStateException(
      "postProcessBeanFactory already called on this post-processor against " + beanFactory);
  }
  this.factoriesPostProcessed.add(factoryId);
  if (!this.registriesPostProcessed.contains(factoryId)) {
    // BeanDefinitionRegistryPostProcessor hook apparently not supported...
    // Simply call processConfigurationClasses lazily at this point then.
    processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
  }

  enhanceConfigurationClasses(beanFactory);
  //注册添加ImportAware接口处理器
  beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
}


private static class ImportAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter {

	....
    
  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof ImportAware) {
      ImportRegistry ir = this.beanFactory.getBean(IMPORT_REGISTRY_BEAN_NAME, ImportRegistry.class);
      AnnotationMetadata importingClass = ir.getImportingClassFor(ClassUtils.getUserClass(bean).getName());
      if (importingClass != null) {
        //ImportAware#setImportMetadata调用
        ((ImportAware) bean).setImportMetadata(importingClass);
      }
    }
    return bean;
  }
}
```



由以上可知ImportAware执行顺序`ApplicationContextAwareProcessor`关联的那些Aware接口之后执行。



#### LoadTimeWeaverAwareProcessor

`AbstractApplicationContext#prepareBeanFactory`

```java
// Detect a LoadTimeWeaver and prepare for weaving, if found.
if (beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {//有条件的注入, 必须存在该bean或者bean definition
  beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
  // Set a temporary ClassLoader for type matching.
  beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
}
```



目前的Aware接口执行顺序如下：

> BeanNameAware -> BeanClassLoaderAware -> BeanFactoryAware -> EnvironmentAware -> EmbeddedValueResolverAware -> ResourceLoaderAware -> ApplicationEventPublisherAware -> MessageSourceAware -> ApplicationContextAware -> ImportAware -> LoadTimeWeaverAware

代码示例运行结果

```
  ------ BeanNameAware::setBeanName invoked
  ------ BeanClassLoaderAware::setBeanClassLoader invoked
  ------ BeanFactoryAware::setBeanFactory invoked
  ------ EnvironmentAware::setEnvironment invoked
  ------ EmbeddedValueResolverAware::setEmbeddedValueResolver invoked
  ------ ResourceLoaderAware::setResourceLoader invoked
  ------ ApplicationEventPublisherAware::setApplicationEventPublisher invoked
  ------ MessageSourceAware::setMessageSource invoked
  ------ ApplicationContextAware::setApplicationContext invoked
  ------ LoadTimeWeaverAware::setLoadTimeWeaver invoked
```



其它的Aware接口都可类似推研, 定制Aware接口也是类似方法, 简单提一下, 通过BeanPostProcessor来进行Aware接口设置调用。



> 注: 本文示例代码地址 https://github.com/rodbate/blog-code/tree/master/java/springframework/src/main/java/com/github/rodbate/blogcode/springframework/aware