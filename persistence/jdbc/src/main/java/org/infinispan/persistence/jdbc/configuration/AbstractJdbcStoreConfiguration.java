package org.infinispan.persistence.jdbc.configuration;

import org.infinispan.commons.configuration.ConfigurationInfo;
import org.infinispan.commons.configuration.attributes.Attribute;
import org.infinispan.commons.configuration.attributes.AttributeDefinition;
import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.configuration.cache.AbstractStoreConfiguration;
import org.infinispan.configuration.cache.AsyncStoreConfiguration;
import org.infinispan.persistence.jdbc.DatabaseType;

public abstract class AbstractJdbcStoreConfiguration extends AbstractStoreConfiguration implements ConfigurationInfo {
   static final AttributeDefinition<Boolean> MANAGE_CONNECTION_FACTORY = AttributeDefinition.builder("manageConnectionFactory", true).immutable().build();
   static final AttributeDefinition<DatabaseType> DIALECT = AttributeDefinition.builder("databaseType", null, DatabaseType.class).immutable().xmlName("dialect").build();
   static final AttributeDefinition<Integer> DB_MAJOR_VERSION = AttributeDefinition.builder("databaseMajorVersion", null, Integer.class).immutable().xmlName("db-major-version").build();
   static final AttributeDefinition<Integer> DB_MINOR_VERSION = AttributeDefinition.builder("databaseMinorVersion", null, Integer.class).immutable().xmlName("db-minor-version").build();
   static final AttributeDefinition<Integer> READ_QUERY_TIMEOUT = AttributeDefinition.builder("readQueryTimeout", 0, Integer.class).xmlName("read-query-timeout").build();
   static final AttributeDefinition<Integer> WRITE_QUERY_TIMEOUT = AttributeDefinition.builder("writeQueryTimeout", 0, Integer.class).xmlName("write-query-timeout").build();

   public static AttributeSet attributeDefinitionSet() {
      return new AttributeSet(AbstractJdbcStoreConfiguration.class, AbstractStoreConfiguration.attributeDefinitionSet(),
                              MANAGE_CONNECTION_FACTORY, DIALECT, DB_MAJOR_VERSION, DB_MINOR_VERSION, READ_QUERY_TIMEOUT, WRITE_QUERY_TIMEOUT);
   }

   private final Attribute<Boolean> manageConnectionFactory;
   private final Attribute<DatabaseType> dialect;
   private final Attribute<Integer> dbMajorVersion;
   private final Attribute<Integer> dbMinorVersion;
   private final Attribute<Integer> readQueryTimeout;
   private final Attribute<Integer> writeQueryTimeout;
   private final ConnectionFactoryConfiguration connectionFactory;

   protected AbstractJdbcStoreConfiguration(AttributeSet attributes, AsyncStoreConfiguration async, ConnectionFactoryConfiguration connectionFactory) {
      super(attributes, async);
      this.connectionFactory = connectionFactory;
      manageConnectionFactory = attributes.attribute(MANAGE_CONNECTION_FACTORY);
      dialect = attributes.attribute(DIALECT);
      dbMajorVersion = attributes.attribute(DB_MAJOR_VERSION);
      dbMinorVersion = attributes.attribute(DB_MINOR_VERSION);
      readQueryTimeout = attributes.attribute(READ_QUERY_TIMEOUT);
      writeQueryTimeout = attributes.attribute(WRITE_QUERY_TIMEOUT);
      subElements.add(connectionFactory);
   }

   public ConnectionFactoryConfiguration connectionFactory() {
      return connectionFactory;
   }

   public boolean manageConnectionFactory() {
      return manageConnectionFactory.get();
   }

   public DatabaseType dialect() {
      return dialect.get();
   }

   public Integer dbMajorVersion() {
      return dbMajorVersion.get();
   }

   public Integer dbMinorVersion() {
      return dbMinorVersion.get();
   }

   public Integer readQueryTimeout() {
      return readQueryTimeout.get();
   }

   public Integer writeQueryTimeout() {
      return writeQueryTimeout.get();
   }

   @Override
   public String toString() {
      return "AbstractJdbcStoreConfiguration [connectionFactory=" + connectionFactory + ", attributes=" + attributes + "]";
   }

   @Override
   public int hashCode() {
      final int prime = 31;
      int result = super.hashCode();
      result = prime * result + ((connectionFactory == null) ? 0 : connectionFactory.hashCode());
      return result;
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj)
         return true;
      if (!super.equals(obj))
         return false;
      if (getClass() != obj.getClass())
         return false;
      AbstractJdbcStoreConfiguration other = (AbstractJdbcStoreConfiguration) obj;
      if (connectionFactory == null) {
         if (other.connectionFactory != null)
            return false;
      } else if (!connectionFactory.equals(other.connectionFactory))
         return false;
      return true;
   }

}
