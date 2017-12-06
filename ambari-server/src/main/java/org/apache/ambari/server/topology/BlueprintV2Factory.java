/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distribut
 * ed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.topology;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.StackV2;
import org.apache.ambari.server.controller.StackV2Factory;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.dao.BlueprintV2DAO;
import org.apache.ambari.server.orm.entities.BlueprintV2Entity;
import org.apache.ambari.server.stack.NoSuchStackException;
import org.apache.ambari.server.state.StackId;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleAbstractTypeResolver;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.inject.Inject;

public class BlueprintV2Factory {
  // Blueprints
  protected static final String BLUEPRINT_NAME_PROPERTY_ID =
    PropertyHelper.getPropertyId("Blueprints", "blueprint_name");
  protected static final String STACK_NAME_PROPERTY_ID =
    PropertyHelper.getPropertyId("Blueprints", "stack_name");
  protected static final String STACK_VERSION_PROPERTY_ID =
    PropertyHelper.getPropertyId("Blueprints", "stack_version");

  // Host Groups
  protected static final String HOST_GROUP_PROPERTY_ID = "host_groups";
//  protected static final String HOST_GROUP_NAME_PROPERTY_ID = "name";
//  protected static final String HOST_GROUP_CARDINALITY_PROPERTY_ID = "cardinality";

  // Host Group Components
//  protected static final String COMPONENT_PROPERTY_ID ="components";
  protected static final String COMPONENT_NAME_PROPERTY_ID ="name";
//  protected static final String COMPONENT_PROVISION_ACTION_PROPERTY_ID = "provision_action";

  // Configurations
//  protected static final String CONFIGURATION_PROPERTY_ID = "configurations";
  protected static final String PROPERTIES_PROPERTY_ID = "properties";
//  protected static final String PROPERTIES_ATTRIBUTES_PROPERTY_ID = "properties_attributes";

//  protected static final String SETTINGS_PROPERTY_ID = "settings";

  private BlueprintV2DAO blueprintDAO;

  private StackV2Factory stackFactory;

  private ObjectMapper objectMapper;

  @Inject
  public BlueprintV2Factory(StackV2Factory stackFactory, BlueprintV2DAO blueprintDAO) {
    this.stackFactory = stackFactory;
    this.blueprintDAO = blueprintDAO;
    createObjectMapper();
  }


  public BlueprintV2 getBlueprint(String blueprintName) throws NoSuchStackException, NoSuchBlueprintException, IOException {
    BlueprintV2Entity entity =
      Optional.ofNullable(blueprintDAO.findByName(blueprintName)).orElseThrow(() -> new NoSuchBlueprintException(blueprintName));
    return convertFromEntity(entity);
  }

  public BlueprintV2 convertFromJson(String json) throws IOException {
    BlueprintV2Impl blueprintV2 = getObjectMapper().readValue(json, BlueprintV2Impl.class);
    blueprintV2.postDeserialization();
    updateStacks(blueprintV2);
    return blueprintV2;
  }

  private void updateStacks(BlueprintV2Impl blueprintV2) {
    Map<StackId, StackV2> stacks = blueprintV2.getRepositoryVersions().stream().collect(Collectors.toMap(
      rv -> new StackId(rv.getStackId()),
      rv -> parseStack(new StackId(rv.getStackId()), rv.getRepositoryVersion())
    ));
    blueprintV2.setStacks(stacks);
  }

  public BlueprintV2 convertFromEntity(BlueprintV2Entity blueprintEntity) throws IOException {
    return convertFromJson(blueprintEntity.getContent());
  }

  public Map<String, Object> convertToMap(BlueprintV2Entity entity) throws IOException {
    return getObjectMapper().readValue(entity.getContent(), new TypeReference<Map<String, Object>>(){});
  }

  private StackV2 parseStack(StackId stackId, String repositoryVersion) {
    try {
      return stackFactory.create(stackId, repositoryVersion);
    } catch (AmbariException e) {
      throw new IllegalArgumentException(
        String.format("Unable to parse stack. name=%s, version=%s, cause: %s",
          stackId.getStackName(),
          stackId.getStackVersion(),
          e.getMessage()),
        e);
    }
  }

  public BlueprintV2Entity convertToEntity(BlueprintV2 blueprint) throws JsonProcessingException {
    BlueprintV2Entity entity = new BlueprintV2Entity();
    String content = convertToJson(blueprint);
    entity.setContent(content);
    entity.setBlueprintName(blueprint.getName());
    entity.setSecurityType(blueprint.getSecurity().getType());
    entity.setSecurityDescriptorReference(blueprint.getSecurity().getDescriptorReference());
    return entity;
  }

  public String convertToJson(BlueprintV2 blueprint) throws JsonProcessingException {
    return getObjectMapper().writeValueAsString(blueprint);

  }

  /**
   * Convert a map of properties to a blueprint entity.
   *
   * @param properties  property map
   * @param securityConfiguration security related properties
   * @return new blueprint entity
   */
  public BlueprintV2 createBlueprint(Map<String, Object> properties, SecurityConfiguration securityConfiguration) throws NoSuchStackException, IOException {
    String name = String.valueOf(properties.get(BLUEPRINT_NAME_PROPERTY_ID));
    // String.valueOf() will return "null" if value is null
    if (name.equals("null") || name.isEmpty()) {
      //todo: should throw a checked exception from here
      throw new IllegalArgumentException("Blueprint name must be provided");
    }
    ObjectMapper om = getObjectMapper();
    String json = om.writeValueAsString(properties);
    BlueprintV2Impl blueprint = om.readValue(json, BlueprintV2Impl.class);
    blueprint.postDeserialization();
    updateStacks(blueprint);
    blueprint.setSecurityConfiguration(securityConfiguration);
    return blueprint;
  }

  public boolean isPrettyPrintJson() {
    return objectMapper.isEnabled(SerializationFeature.INDENT_OUTPUT);
  }

  public void setPrettyPrintJson(boolean prettyPrintJson) {
    if (prettyPrintJson) {
      objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    else {
      objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
    }
  }

  public ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  private void createObjectMapper() {
    objectMapper = new ObjectMapper();
    SimpleModule module = new SimpleModule("CustomModel", Version.unknownVersion());
    SimpleAbstractTypeResolver resolver = new SimpleAbstractTypeResolver();
    resolver.addMapping(HostGroupV2.class, HostGroupV2Impl.class);
    module.setAbstractTypes(resolver);
    objectMapper.registerModule(module);
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

}