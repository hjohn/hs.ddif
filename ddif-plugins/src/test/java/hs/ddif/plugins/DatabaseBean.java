package hs.ddif.plugins;

import hs.ddif.test.plugin.Database;

public class DatabaseBean implements Database {

  @Override
  public String getType() {
    return "dbbean";
  }

}
