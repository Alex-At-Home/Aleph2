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
package com.ikanow.aleph2.data_model.objects.data_import;

import java.util.List;
import java.util.Map;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Bean controlling an enrichment configuration
 * @author acp
 */
public class EnrichmentControlMetadataBean {
	
	public EnrichmentControlMetadataBean() {}
	
	/** User constructor
	 */
	public EnrichmentControlMetadataBean(@NonNull String name,
			@Nullable List<String> dependencies, @NonNull Boolean enabled,
			@Nullable List<String> library_ids_or_names, @Nullable Map<String, Object> config) {
		super();
		this.name = name;
		this.dependencies = dependencies;
		this.enabled = enabled;
		this.library_ids_or_names = library_ids_or_names;
		this.config = config;
	}
	/** The name of the enrichment - must be unique within the list of enrichments in this bucket (used for search/display/dependencies)
	 * @return the name
	 */
	public String name() {
		return name;
	}
	/** Defines the dependency order of enrichment - this can be used by the framework to optimize runtime
	 * @return the dependencies
	 */
	public List<String> dependencies() {
		return dependencies;
	}
	/** Returns if this enrichment is currently enabled - implicitly disables all dependent enrichments
	 * @return the enabled
	 */
	public Boolean enabled() {
		return enabled;
	}
	/** A list of ids or names (within either the bucket or global library) of enrichment JARs to be
	 *  used as part of this enrichment. Exactly on of the JARs must be of type IEnrichmentLibraryModule  
	 * @return the library_ids_or_names
	 */
	public List<String> library_ids_or_names() {
		return library_ids_or_names;
	}
	/** The enrichment-module-specific configuration that controls the per-bucket enrichment
	 * @return the config
	 */
	public Map<String, Object> getConfig() {
		return config;
	}
	private String name;
	private List<String> dependencies;
	private Boolean enabled;
	private List<String> library_ids_or_names;
	private Map<String, Object> config;
}	
