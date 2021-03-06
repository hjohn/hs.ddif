package hs.ddif.plugins;

import hs.ddif.core.Injector;
import hs.ddif.core.inject.instantiator.BeanResolutionException;
import hs.ddif.test.plugin.Database;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PluginSingletonTest {
  private static final URL PLUGIN_URL;

  static {
    try {
      PLUGIN_URL = PluginManagerTest.class.getResource("/plugins/ddif-test-plugin-singleton-1.0.0-SNAPSHOT.jar").toURI().toURL();
    }
    catch(MalformedURLException | URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void shouldLoadAndUnloadPluginWithPluginSingleton() throws BeanResolutionException {
    PluginScopeResolver pluginScopeResolver = new PluginScopeResolver();
    Injector injector = new Injector(true, pluginScopeResolver);

    PluginManager pluginManager = new PluginManager(injector.getStore(), pluginScopeResolver);

    for(int i = 0; i < 5; i++) {
      Plugin plugin = pluginManager.loadPluginAndScan(PLUGIN_URL);

      assertNotNull(injector.getInstance(Database.class));

      pluginManager.unload(plugin);

      assertThrows(BeanResolutionException.class, () -> injector.getInstance(Database.class));

      waitForPluginUnload(plugin);

      assertTrue(plugin.isUnloaded());
    }
  }

  private static void waitForPluginUnload(Plugin plugin) {
    for(int i = 0; i < 20; i++) {
      System.gc();

      if(plugin.isUnloaded()) {
        break;
      }

      try {
        Thread.sleep(100);
      }
      catch(InterruptedException e) {
      }
    }
  }
}
