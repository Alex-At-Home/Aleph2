/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.ikanow.aleph2.data_model.utils;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.ikanow.aleph2.data_model.interfaces.data_access.samples.ICustomService;
import com.ikanow.aleph2.data_model.interfaces.data_access.samples.SampleCustomServiceOne;
import com.ikanow.aleph2.data_model.interfaces.data_access.samples.SampleCustomServiceTwo;
import com.ikanow.aleph2.data_model.interfaces.data_services.samples.SampleSecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.typesafe.config.ConfigFactory;

public class TestModuleUtils {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}
	
	@Test
	public void testLoadModulesFromConfig() throws Exception {
		Map<String, Object> configMap = new HashMap<String, Object>();		
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());			
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));
		
		SampleCustomServiceOne service_one = ModuleUtils.getService(SampleCustomServiceOne.class, Optional.empty());
		assertNotNull(service_one);
	}
	
	@Test
	public void testValidateOnlyOneDefault() {
		Map<String, Object> configMap = new HashMap<String, Object>();
		configMap.put("service.SampleCustomServiceOne.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());	
		configMap.put("service.SampleCustomServiceOne.default", true);
		configMap.put("service.SampleCustomServiceTwo.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceTwo.service", SampleCustomServiceTwo.class.getCanonicalName());	
		configMap.put("service.SampleCustomServiceTwo.default", true);
		boolean threwException = false;
		try {
			ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));
		} catch (Exception ex ){
			threwException = true;
		}
		assertTrue(threwException);
	}
	
	@Test
	public void testGetExtraDepedencyModules() throws Exception {
		Map<String, Object> configMap = new HashMap<String, Object>();
		configMap.put("service.SampleCustomServiceOne.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());	
		configMap.put("service.SampleCustomServiceOne.default", true);		
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));
		
		ICustomService service_one = ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceOne.class.getSimpleName()));
		assertNotNull(service_one);
	}
	
	@Test
	public void testNoDefaultAutoSet() throws Exception {
		//test that the default services names are set as default unless they get overrode
		Map<String, Object> configMap = new HashMap<String, Object>();
		configMap.put("service.SecurityService.interface", ISecurityService.class.getCanonicalName());
		configMap.put("service.SecurityService.service", SampleSecurityService.class.getCanonicalName());
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));		
		assertNotNull( ModuleUtils.getService(ISecurityService.class, Optional.empty()));
	}
	
	/**
	 * Test that we can have 2 services loaded with the same interface (they
	 * must use different annotations so they don't collide).
	 * @throws Exception 
	 * 
	 */
	@Test
	public void testTwoServicesWithSameInterface() throws Exception {
		Map<String, Object> configMap = new HashMap<String, Object>();
		configMap.put("service.SampleCustomServiceOne.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());			
		configMap.put("service.SampleCustomServiceTwo.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceTwo.service", SampleCustomServiceTwo.class.getCanonicalName());	
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));
		
		ICustomService service_one = ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceOne.class.getSimpleName()));
		ICustomService service_two = ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceTwo.class.getSimpleName()));
		//They will be separate instances so they will not hash to the same code, but should be the same parent class		
		assertNotEquals(service_one.getClass(), service_two.getClass());
		assertNotEquals(service_one.hashCode(), service_two.hashCode());
		assertNotNull((SampleCustomServiceOne)service_one);
		assertNotNull((SampleCustomServiceTwo)service_two);
	}
	
	@Test
	public void testDefaultAndNamed() throws Exception {
		Map<String, Object> configMap = new HashMap<String, Object>();
		configMap.put("service.SampleCustomServiceOne.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceOne.default", true);		
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));
		
		ICustomService service_one = ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceOne.class.getSimpleName()));
		ICustomService service_two = ModuleUtils.getService(ICustomService.class, Optional.empty());
		//They will be separate instances so they will not hash to the same code, but should be the same parent class
		assertEquals(service_one.getClass(), service_two.getClass());
		
		//this is making the same check as above, just being explicit for brevity
		SampleCustomServiceOne service_one_casted = (SampleCustomServiceOne) service_one;
		assertEquals(service_one_casted.getClass(), service_two.getClass());
	}
	
	/**
	 * Creates 2 services that bind custom dependencies to the same interface but
	 * 2 different implementations, make sure they each get the correct implementation.
	 * @throws Exception 
	 * 
	 */
	@Test
	public void testNestedDependenciesDontCollide() throws Exception {
		Map<String, Object> configMap = new HashMap<String, Object>();		
		configMap.put("service.SampleCustomServiceOne.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());			
		configMap.put("service.SampleCustomServiceTwo.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceTwo.service", SampleCustomServiceTwo.class.getCanonicalName());
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));		
		SampleCustomServiceOne service_one = (SampleCustomServiceOne) ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceOne.class.getSimpleName()));
		System.out.println(service_one.dep.getANumber());
		SampleCustomServiceTwo service_two = (SampleCustomServiceTwo) ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceTwo.class.getSimpleName()));
		
		assertNotEquals(service_one.dep.getANumber(), service_two.dep.getANumber());
	}
	
	@Test
	public void testServicesAreSingletons() throws Exception {
		Map<String, Object> configMap = new HashMap<String, Object>();
		configMap.put("service.SampleCustomServiceOne.interface", ICustomService.class.getCanonicalName());
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());	
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));
		
		ICustomService service_one = ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceOne.class.getSimpleName()));
		ICustomService service_two = ModuleUtils.getService(ICustomService.class, Optional.of(SampleCustomServiceOne.class.getSimpleName()));
		
		//They should be the same instance	
		assertEquals(service_one.hashCode(), service_two.hashCode());
	}
	
	@Test
	public void testGetServiceWithoutInterface() throws Exception {
		Map<String, Object> configMap = new HashMap<String, Object>();
		configMap.put("service.SampleCustomServiceOne.service", SampleCustomServiceOne.class.getCanonicalName());	
		ModuleUtils.loadModulesFromConfig(ConfigFactory.parseMap(configMap));
		
		SampleCustomServiceOne service_one = ModuleUtils.getService(SampleCustomServiceOne.class, Optional.empty());
		assertNotNull(service_one);
	}
}
