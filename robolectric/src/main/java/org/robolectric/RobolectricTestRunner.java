package org.robolectric;

import android.app.Application;
import android.os.Build;
import org.jetbrains.annotations.TestOnly;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;
import org.robolectric.annotation.*;
import org.robolectric.bytecode.*;
import org.robolectric.internal.ParallelUniverse;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.res.*;
import org.robolectric.util.AnnotationUtil;
import org.robolectric.util.Pair;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.*;
import java.net.URL;
import java.security.SecureRandom;
import java.util.*;

/**
 * Installs a {@link org.robolectric.bytecode.InstrumentingClassLoader} and
 * {@link org.robolectric.res.ResourceLoader} in order to
 * provide a simulation of the Android runtime environment.
 */
public class RobolectricTestRunner extends BlockJUnit4ClassRunner {
  private static final Map<Class<? extends RobolectricTestRunner>, EnvHolder> envHoldersByTestRunner = new HashMap<Class<? extends RobolectricTestRunner>, EnvHolder>();
  private static Map<Pair<AndroidManifest, SdkConfig>, ResourceLoader> resourceLoadersByManifestAndConfig = new HashMap<Pair<AndroidManifest, SdkConfig>, ResourceLoader>();
  private static ShadowMap mainShadowMap;
  private final EnvHolder envHolder;
  private TestLifecycle<Application> testLifecycle;
  private DependencyResolver dependencyResolver;

  static {
    new SecureRandom(); // this starts up the Poller SunPKCS11-Darwin thread early, outside of any Robolectric classloader
  }

  private Class<? extends RobolectricTestRunner> lastTestRunnerClass;
  private SdkConfig lastSdkConfig;
  private SdkEnvironment lastSdkEnvironment;
  private final HashSet<Class<?>> loadedTestClasses = new HashSet<Class<?>>();

  /**
   * Creates a runner to run {@code testClass}. Looks in your working directory for your AndroidManifest.xml file
   * and res directory by default. Use the {@link Config} annotation to configure.
   *
   * @param testClass the test class to be run
   * @throws InitializationError if junit says so
   */
  public RobolectricTestRunner(final Class<?> testClass) throws InitializationError {
    super(testClass);

    EnvHolder envHolder;
    synchronized (envHoldersByTestRunner) {
      Class<? extends RobolectricTestRunner> testRunnerClass = getClass();
      envHolder = envHoldersByTestRunner.get(testRunnerClass);
      if (envHolder == null) {
        envHolder = new EnvHolder();
        envHoldersByTestRunner.put(testRunnerClass, envHolder);
      }
    }
    this.envHolder = envHolder;
  }

  private void assureTestLifecycle(SdkEnvironment sdkEnvironment) {
    try {
      ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();
      testLifecycle = (TestLifecycle) robolectricClassLoader.loadClass(getTestLifecycleClass().getName()).newInstance();
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  protected DependencyResolver getJarResolver() {
    if (dependencyResolver == null) {
      if (Boolean.getBoolean("robolectric.offline")) {
        String dependencyDir = System.getProperty("robolectric.dependency.dir", ".");
        dependencyResolver = new LocalDependencyResolver(new File(dependencyDir));
      } else {
        File cacheDir = new File(new File(System.getProperty("java.io.tmpdir")), "robolectric");
        cacheDir.mkdir();

        if (cacheDir.exists()) {
          dependencyResolver = new CachedDependencyResolver(new MavenDependencyResolver(), cacheDir, 60 * 60 * 24 * 1000);
        } else {
          dependencyResolver = new MavenDependencyResolver();
        }
      }
    }

    return dependencyResolver;
  }

  public SdkEnvironment createSdkEnvironment(SdkConfig sdkConfig) {
    Setup setup = createSetup();
    ClassLoader robolectricClassLoader = createRobolectricClassLoader(setup, sdkConfig);
    return new SdkEnvironment(sdkConfig, robolectricClassLoader);
  }

  protected ClassHandler createClassHandler(ShadowMap shadowMap, SdkConfig sdkConfig) {
    return new ShadowWrangler(shadowMap, sdkConfig);
  }

  protected AndroidManifest createAppManifest(FsFile manifestFile, FsFile resDir, FsFile assetsDir) {
    if (!manifestFile.exists()) {
      System.out.print("WARNING: No manifest file found at " + manifestFile.getPath() + ".");
      System.out.println("Falling back to the Android OS resources only.");
      System.out.println("To remove this warning, annotate your test class with @Config(manifest=Config.NONE).");
      return null;
    }
    AndroidManifest manifest = new AndroidManifest(manifestFile, resDir, assetsDir);
    String packageName = System.getProperty("android.package");
    manifest.setPackageName(packageName);
    return manifest;
  }

  public Setup createSetup() {
    return new Setup();
  }

  protected Class<? extends TestLifecycle> getTestLifecycleClass() {
    return DefaultTestLifecycle.class;
  }

  protected ClassLoader createRobolectricClassLoader(Setup setup, SdkConfig sdkConfig) {
    URL[] urls = getJarResolver().getLocalArtifactUrls(sdkConfig.getSdkClasspathDependencies());
    return new AsmInstrumentingClassLoader(setup, urls);
  }

  public static void injectClassHandler(ClassLoader robolectricClassLoader, ClassHandler classHandler) {
    String className = RobolectricInternals.class.getName();
    Class<?> robolectricInternalsClass = ReflectionHelpers.loadClassReflectively(robolectricClassLoader, className);
    ReflectionHelpers.setStaticFieldReflectively(robolectricInternalsClass, "classHandler", classHandler);
  }

  @Override
  protected Statement classBlock(RunNotifier notifier) {
    final Statement statement = childrenInvoker(notifier);
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          statement.evaluate();
          for (Class<?> testClass : loadedTestClasses) {
            invokeAfterClass(testClass);
          }
        } finally {
          afterClass();
        }
      }
    };
  }

  private void invokeAfterClass(final Class<?> clazz) throws Throwable {
    final TestClass testClass = new TestClass(clazz);
    final List<FrameworkMethod> afters = testClass.getAnnotatedMethods(AfterClass.class);
    for (FrameworkMethod after : afters) {
      after.invokeExplosively(null);
    }
  }

  @Override protected Statement methodBlock(final FrameworkMethod method) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        final Config config = getConfig(method.getMethod());
        AndroidManifest appManifest = getAppManifest(config);
        SdkEnvironment sdkEnvironment = getEnvironment(appManifest, config);
        Thread.currentThread().setContextClassLoader(sdkEnvironment.getRobolectricClassLoader());

        Class bootstrappedTestClass = sdkEnvironment.bootstrappedClass(getTestClass().getJavaClass());
        HelperTestRunner helperTestRunner = getHelperTestRunner(bootstrappedTestClass);

        final Method bootstrappedMethod;
        try {
          //noinspection unchecked
          bootstrappedMethod = bootstrappedTestClass.getMethod(method.getName());
        } catch (NoSuchMethodException e) {
          throw new RuntimeException(e);
        }

        configureShadows(sdkEnvironment, config);

        ParallelUniverseInterface parallelUniverseInterface = getHooksInterface(sdkEnvironment);
        try {
          // Only invoke @BeforeClass once per class
          if (!loadedTestClasses.contains(bootstrappedTestClass)) {
            invokeBeforeClass(bootstrappedTestClass);
          }
          assureTestLifecycle(sdkEnvironment);

          parallelUniverseInterface.resetStaticState(config);
          parallelUniverseInterface.setSdkConfig(sdkEnvironment.getSdkConfig());

          int sdkVersion = pickReportedSdkVersion(config, appManifest);
          Class<?> versionClass = sdkEnvironment.bootstrappedClass(Build.VERSION.class);
          Field sdk_int = versionClass.getDeclaredField("SDK_INT");
          sdk_int.setAccessible(true);
          Field modifiers = Field.class.getDeclaredField("modifiers");
          modifiers.setAccessible(true);
          modifiers.setInt(sdk_int, sdk_int.getModifiers() & ~Modifier.FINAL);
          sdk_int.setInt(null, sdkVersion);

          ResourceLoader systemResourceLoader = sdkEnvironment.getSystemResourceLoader(getJarResolver());
          setUpApplicationState(bootstrappedMethod, parallelUniverseInterface, systemResourceLoader, appManifest, config);
          testLifecycle.beforeTest(bootstrappedMethod);
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }

        final Statement statement = helperTestRunner.methodBlock(new FrameworkMethod(bootstrappedMethod));

        // todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
        try {
          statement.evaluate();
        } finally {
          try {
            parallelUniverseInterface.tearDownApplication();
          } finally {
            try {
              internalAfterTest(bootstrappedMethod);
            } finally {
              parallelUniverseInterface.resetStaticState(config); // afterward too, so stuff doesn't hold on to classes?
              // todo: is this really needed?
              Thread.currentThread().setContextClassLoader(RobolectricTestRunner.class.getClassLoader());
            }
          }
        }
      }
    };
  }

  private void invokeBeforeClass(final Class clazz) throws Throwable {
    if (!loadedTestClasses.contains(clazz)) {
      loadedTestClasses.add(clazz);

      final TestClass testClass = new TestClass(clazz);
      final List<FrameworkMethod> befores = testClass.getAnnotatedMethods(BeforeClass.class);
      for (FrameworkMethod before : befores) {
        before.invokeExplosively(null);
      }
    }
  }

  protected HelperTestRunner getHelperTestRunner(Class bootstrappedTestClass) {
    try {
      return new HelperTestRunner(bootstrappedTestClass);
    } catch (InitializationError initializationError) {
      throw new RuntimeException(initializationError);
    }
  }

  private SdkEnvironment getEnvironment(final AndroidManifest appManifest, final Config config) {
    final SdkConfig sdkConfig = pickSdkVersion(appManifest, config);

    // keep the most recently-used SdkEnvironment strongly reachable to prevent thrashing in low-memory situations.
    if (getClass().equals(lastTestRunnerClass) && sdkConfig.equals(lastSdkConfig)) {
      return lastSdkEnvironment;
    }

    lastTestRunnerClass = null;
    lastSdkConfig = null;
    lastSdkEnvironment = envHolder.getSdkEnvironment(sdkConfig, new SdkEnvironment.Factory() {
      @Override public SdkEnvironment create() {
        return createSdkEnvironment(sdkConfig);
      }
    });
    lastTestRunnerClass = getClass();
    lastSdkConfig = sdkConfig;
    return lastSdkEnvironment;
  }

  protected SdkConfig pickSdkVersion(AndroidManifest appManifest, Config config) {
    if (config != null && config.emulateSdk() > 0) {
      return new SdkConfig(config.emulateSdk());
    } else {
      if (appManifest != null) {
        return new SdkConfig(appManifest.getTargetSdkVersion());
      } else {
        return SdkConfig.getDefaultSdk();
      }
    }
  }

  protected AndroidManifest getAppManifest(Config config) {
    if (config.manifest().equals(Config.NONE)) {
      return null;
    }

    String manifestProperty = System.getProperty("android.manifest");
    String resourcesProperty = System.getProperty("android.resources");
    String assetsProperty = System.getProperty("android.assets");

    FsFile baseDir;
    FsFile manifestFile;
    FsFile resDir;
    FsFile assetsDir;

    boolean defaultManifest = config.manifest().equals(Config.DEFAULT);
    if (defaultManifest && manifestProperty != null) {
      manifestFile = Fs.fileFromPath(manifestProperty);
      baseDir = manifestFile.getParent();
      resDir = Fs.fileFromPath(resourcesProperty);
      assetsDir = Fs.fileFromPath(assetsProperty);
    } else {
      manifestFile = getBaseDir().join(defaultManifest ? AndroidManifest.DEFAULT_MANIFEST_NAME : config.manifest());
      baseDir = manifestFile.getParent();
      resDir = baseDir.join(config.resourceDir());
      assetsDir = baseDir.join(AndroidManifest.DEFAULT_ASSETS_FOLDER);
    }

    List<FsFile> libraryDirs = null;
    if (config.libraries().length > 0) {
      libraryDirs = new ArrayList<FsFile>();
      for (String libraryDirName : config.libraries()) {
        libraryDirs.add(baseDir.join(libraryDirName));
      }
    }

    synchronized (envHolder) {
      AndroidManifest appManifest;
      appManifest = envHolder.appManifestsByFile.get(manifestFile);
      if (appManifest == null) {
        long startTime = System.currentTimeMillis();
        appManifest = createAppManifest(manifestFile, resDir, assetsDir);

        if (libraryDirs != null) {
          appManifest.setLibraryDirectories(libraryDirs);
        }

        if (DocumentLoader.DEBUG_PERF)
          System.out.println(String.format("%4dms spent in %s", System.currentTimeMillis() - startTime, manifestFile));

        envHolder.appManifestsByFile.put(manifestFile, appManifest);
      }
      return appManifest;
    }
  }

  protected FsFile getBaseDir() {
    return Fs.currentDirectory();
  }

  public Config getConfig(Method method) {
    Config config = AnnotationUtil.defaultsFor(Config.class);

    Config globalConfig = Config.Implementation.fromProperties(getConfigProperties());
    if (globalConfig != null) {
      config = new Config.Implementation(config, globalConfig);
    }

    Config methodClassConfig = method.getDeclaringClass().getAnnotation(Config.class);
    if (methodClassConfig != null) {
      config = new Config.Implementation(config, methodClassConfig);
    }

    Config testClassConfig = getTestClass().getJavaClass().getAnnotation(Config.class);
    if (testClassConfig != null) {
      config = new Config.Implementation(config, testClassConfig);
    }

    Config methodConfig = method.getAnnotation(Config.class);
    if (methodConfig != null) {
      config = new Config.Implementation(config, methodConfig);
    }

    return config;
  }

  protected Properties getConfigProperties() {
    ClassLoader classLoader = getTestClass().getClass().getClassLoader();
    InputStream resourceAsStream = classLoader.getResourceAsStream("org.robolectric.Config.properties");
    if (resourceAsStream == null) return null;
    Properties properties = new Properties();
    try {
      properties.load(resourceAsStream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return properties;
  }

  protected void configureShadows(SdkEnvironment sdkEnvironment, Config config) {
    ShadowMap shadowMap = createShadowMap();

    if (config != null) {
      Class<?>[] shadows = config.shadows();
      if (shadows.length > 0) {
        shadowMap = shadowMap.newBuilder()
            .addShadowClasses(shadows)
            .build();
      }
    }

    ClassHandler classHandler = getClassHandler(sdkEnvironment, shadowMap);
    injectClassHandler(sdkEnvironment.getRobolectricClassLoader(), classHandler);
  }

  private ClassHandler getClassHandler(SdkEnvironment sdkEnvironment, ShadowMap shadowMap) {
    ClassHandler classHandler;
    synchronized (sdkEnvironment) {
      classHandler = sdkEnvironment.classHandlersByShadowMap.get(shadowMap);
      if (classHandler == null) {
        classHandler = createClassHandler(shadowMap, sdkEnvironment.getSdkConfig());
      }
    }
    return classHandler;
  }

  protected void setUpApplicationState(Method method, ParallelUniverseInterface parallelUniverseInterface, ResourceLoader systemResourceLoader, AndroidManifest appManifest, Config config) {
    parallelUniverseInterface.setUpApplicationState(method, testLifecycle, systemResourceLoader, appManifest, config);
  }

  private int getTargetSdkVersion(AndroidManifest appManifest) {
    return getTargetVersionWhenAppManifestMightBeNullWhaaa(appManifest);
  }

  public int getTargetVersionWhenAppManifestMightBeNullWhaaa(AndroidManifest appManifest) {
    return appManifest == null // app manifest would be null for libraries
        ? Build.VERSION_CODES.ICE_CREAM_SANDWICH // todo: how should we be picking this?
        : appManifest.getTargetSdkVersion();
  }

  protected int pickReportedSdkVersion(Config config, AndroidManifest appManifest) {
    if (config != null && config.reportSdk() != -1) {
      return config.reportSdk();
    } else {
      return getTargetSdkVersion(appManifest);
    }
  }

  private ParallelUniverseInterface getHooksInterface(SdkEnvironment sdkEnvironment) {
    ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();
    try {
      Class<?> clazz = robolectricClassLoader.loadClass(ParallelUniverse.class.getName());
      Class<? extends ParallelUniverseInterface> typedClazz = clazz.asSubclass(ParallelUniverseInterface.class);
      Constructor<? extends ParallelUniverseInterface> constructor = typedClazz.getConstructor(RobolectricTestRunner.class);
      return constructor.newInstance(this);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public void internalAfterTest(final Method method) {
    testLifecycle.afterTest(method);
  }

  private void afterClass() {
    testLifecycle = null;
  }

  @TestOnly
  boolean allStateIsCleared() {
    return testLifecycle == null;
  }

  @Override
  public Object createTest() throws Exception {
    throw new UnsupportedOperationException("this should always be invoked on the HelperTestRunner!");
  }

  public final ResourceLoader getAppResourceLoader(SdkConfig sdkConfig, ResourceLoader systemResourceLoader, final AndroidManifest appManifest) {
    Pair<AndroidManifest, SdkConfig> androidManifestSdkConfigPair = new Pair<AndroidManifest, SdkConfig>(appManifest, sdkConfig);
    ResourceLoader resourceLoader = resourceLoadersByManifestAndConfig.get(androidManifestSdkConfigPair);
    if (resourceLoader == null) {
      resourceLoader = createAppResourceLoader(systemResourceLoader, appManifest);
      resourceLoadersByManifestAndConfig.put(androidManifestSdkConfigPair, resourceLoader);
    }
    return resourceLoader;
  }

  protected ResourceLoader createAppResourceLoader(ResourceLoader systemResourceLoader, AndroidManifest appManifest) {
    List<PackageResourceLoader> appAndLibraryResourceLoaders = new ArrayList<PackageResourceLoader>();
    for (ResourcePath resourcePath : appManifest.getIncludedResourcePaths()) {
      appAndLibraryResourceLoaders.add(createResourceLoader(resourcePath));
    }
    OverlayResourceLoader overlayResourceLoader = new OverlayResourceLoader(appManifest.getPackageName(), appAndLibraryResourceLoaders);

    Map<String, ResourceLoader> resourceLoaders = new HashMap<String, ResourceLoader>();
    resourceLoaders.put("android", systemResourceLoader);
    resourceLoaders.put(appManifest.getPackageName(), overlayResourceLoader);
    return new RoutingResourceLoader(resourceLoaders);
  }

  public PackageResourceLoader createResourceLoader(ResourcePath resourcePath) {
    return new PackageResourceLoader(resourcePath);
  }

  protected ShadowMap createShadowMap() {
    synchronized (RobolectricTestRunner.class) {
      if (mainShadowMap != null) return mainShadowMap;

      mainShadowMap = new ShadowMap.Builder()
          //.addShadowClasses(Shadows.DEFAULT_SHADOW_CLASSES)
          .build();
      //mainShadowMap = new ShadowMap.Builder()
      //        .addShadowClasses(Shadows.DEFAULT_SHADOW_CLASSES)
      //        .build();
      return mainShadowMap;
    }
  }

  public class HelperTestRunner extends BlockJUnit4ClassRunner {
    public HelperTestRunner(Class<?> testClass) throws InitializationError {
      super(testClass);
    }

    @Override protected Object createTest() throws Exception {
      Object test = super.createTest();
      testLifecycle.prepareTest(test);
      return test;
    }

    @Override public Statement classBlock(RunNotifier notifier) {
      return super.classBlock(notifier);
    }

    @Override public Statement methodBlock(FrameworkMethod method) {
      return super.methodBlock(method);
    }
  }
}
