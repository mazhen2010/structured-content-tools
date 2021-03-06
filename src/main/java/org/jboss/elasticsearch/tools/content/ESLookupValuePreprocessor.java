/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.elasticsearch.tools.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;

/**
 * Content preprocessor which allows to Look up value over Elasticsearch search request containing some value from data
 * structure. This preprocessor requires Elasticsearch client to be passed into
 * {@link #init(String, org.elasticsearch.client.Client, Map)}! Example of configuration for this preprocessor for
 * single value lookup:
 * 
 * <pre>
 * { 
 *     "name"     : "EL Lookup transformer",
 *     "class"    : "org.jboss.elasticsearch.tools.content.ESLookupValuePreprocessor",
 *     "settings" : {
 *         "index_name"        : "projects",
 *         "index_type"        : "project",
 *         "source_field"      : "jira_project_code",
 *         "idx_search_field"  : "jira_project_code",
 *         "result_mapping"    : [ 
 *             {"idx_result_field":"universal_code", "target_field":"project_code", "value_default":"unknown"},
 *             {"idx_result_field":"name", "target_field":"project_name"} 
 *         ]
 *     } 
 * }
 * </pre>
 * 
 * Options are:
 * <ul>
 * <li><code>index_name<code> - name of search index to lookup values in
 * <li><code>index_type<code> - name of type in search index to lookup values in
 * <li><code>source_field<code> - source field in input data to be used as 'lookup key'. Dot notation for nested values can be used here.
 * <li><code>source_value<code> - value to be used as 'lookup key'. Can be used as alternative instead of <code>source_field<code>. 
 * You can use pattern for keys replacement with values from input data here. Keys are enclosed in curly braces, dot notation for deeper nesting may be used in keys.  
 * <li><code>idx_search_field<code> - field in search index document to be asked for 'lookup key' obtained from source field. Elasticsearch <code>text</code>
 * filter is used against this field. Search is not performed if 'lookup key' is empty. This configuration can contain
 * array of lookup field's names also. They are looked up in sequence then, if previous one do not provide any concrete
 * value - so it is sort of fallback mechanism.
 * <li><code>result_multiple_ignore</code> - defines what to do if lookup returns multiple value. If `false` (default
 * value) then first result is used. If `true` then lookup result is ignored and default value is used if any. Warning
 * message is produced for both cases.
 * <li>
 * <code>result_mapping<code> - array of mappings from lookup result to the data. Each mapping definition may contain these fields:
 * <ul>
 * <li><code>idx_result_field<code> - field in found search index document to be placed into target field. You can use `_source` value there to get whole content of document found in ES index.
 * <li><code>target_field<code> - target field in data to store looked up value into. Can be same as input field. Dot
 * notation can be used here for structure nesting.
 * <li><code>value_default</code> - optional default value used if lookup do not provide value. If not set then target
 * field is leaved empty for values not found in mapping. You can use pattern for keys replacement from other values in
 * data in default value. Keys are enclosed in curly braces, dot notation for deeper nesting may be used in keys.
 * Special key '<code>__original</code>' means that original value from source field will be used here. Example of
 * default value with replacement keys ' <code>No mapping found for value {__original}.</code> '.
 * </ul>
 * <li><code>source_bases</code> - list of fields in source data which are used as bases for lookups evaluation. If
 * defined then lookup is performed for each of this fields, <code>source_field</code>, <code>target_field</code> and
 * keys in <code>value_default</code> and<code>source_value</code> are resolved relatively against this base. Base must
 * provide object or list of objects. See example later. </ul>
 * 
 * 
 * Example of configuration for this preprocessor for lookup of multiple values of same structure:
 * 
 * <pre>
 * { 
 *     "name"     : "EL Lookup transformer",
 *     "class"    : "org.jboss.elasticsearch.tools.content.ESLookupValuePreprocessor",
 *     "settings" : {
 *         "index_name"        : "peoples",
 *         "index_type"        : "person",
 *         "source_field"      : "username",
 *         "idx_search_field"  : "user_name",
 *         "result_mapping"    : [
 *             {"idx_result_field":"full_name", "target_field":"name"},
 *             {"idx_result_field":"email_company", "target_field":"email"},
 *             {"idx_result_field":"phone_company", "target_field":"phone"}
 *         ],
 *         "source_bases"      : ["author", "editor", "comments.author"]
 *     } 
 * }
 * </pre>
 * 
 * So for example next input document:
 * 
 * <pre>
 * {
 *   "author": {
 *     "username" : "joe"
 *   },
 *   "editor": {
 *     "username" : "dan"
 *   },
 *   "comments" : [
 *     {
 *       "text": "Posted first version",
 *       "author": {
 *         "username" : "joe"
 *        }
 *     },{
 *       "text": "We need to work on it little more.",
 *       "author": {
 *         "username" : "dan"
 *        }
 *     }
 *   ]
 * }
 * </pre>
 * 
 * will be transformed to the:
 * 
 * <pre>
 * {
 *   "author": {
 *     "username" : "joe",
 *     "name"     : "Joe Gulf",
 *     "email"    : "joe@edit.com",
 *     "phone"    : "11223355"
 *   },
 *   "editor": {
 *     "username" : "dan"
 *     "name"     : "Dan Doe",
 *     "email"    : "dan@edit.com",
 *     "phone"    : "11223356"
 *   },
 *   "comments" : [
 *     {
 *       "text": "Posted first version",
 *       "author": {
 *         "username" : "joe",
 *         "name"     : "Joe Gulf",
 *         "email"    : "joe@edit.com",
 *         "phone"    : "11223355"
 *        }
 *     },{
 *       "text": "We need to work on it little more.",
 *       "author": {
 *         "username" : "dan",
 *         "name"     : "Dan Doe",
 *         "email"    : "dan@edit.com",
 *         "phone"    : "11223356"
 *        }
 *     }
 *   ]
 * }
 * 
 * <pre>
 * if there will be necessary documents in Elasticsearch index called <code>peoples</code> with type <code>people</code>
 * with content like:
 * 
 * <pre>
 * { 
 *   "user_name"     : "joe",
 *   "full_name"     : "Joe Gulf",
 *   "email_company" : "joe@edit.com",
 *   "phone_company" : "11223355"
 *  }
 * </pre>
 * 
 * and
 * 
 * <pre>
 * { 
 *   "user_name"     : "dan",
 *   "full_name"     : "Dan Doe",
 *   "email_company" : "dan@edit.com",
 *   "phone_company" : "11223356"
 *  }
 * </pre>
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 * @see StructuredContentPreprocessorFactory
 */
public class ESLookupValuePreprocessor extends
		StructuredContentPreprocessorWithSourceBasesBase<ESLookupValuePreprocessor.LookupContenxt> {

	protected static final String CFG_index_name = "index_name";
	protected static final String CFG_index_type = "index_type";
	protected static final String CFG_source_field = "source_field";
	protected static final String CFG_source_value = "source_value";
	protected static final String CFG_idx_search_field = "idx_search_field";
	protected static final String CFG_result_mapping = "result_mapping";
	protected static final String CFG_idx_result_field = "idx_result_field";
	protected static final String CFG_ignore_multiple_results = "result_multiple_ignore";
	protected static final String CFG_target_field = "target_field";
	protected static final String CFG_value_default = "value_default";

	protected String indexName;
	protected String indexType;
	protected String sourceField;
	protected String sourceValuePattern;
	protected List<String> idxSearchField;
	protected List<Map<String, String>> resultMapping;
	protected boolean ignoreMultipleResults = false;

	@SuppressWarnings("unchecked")
	@Override
	public void init(Map<String, Object> settings) throws SettingsException {
		super.init(settings);
		if (client == null) {
			throw new SettingsException("ElasticSearch client is required for preprocessor " + name);
		}

		indexName = XContentMapValues.nodeStringValue(settings.get(CFG_index_name), null);
		validateConfigurationStringNotEmpty(indexName, CFG_index_name);
		indexType = XContentMapValues.nodeStringValue(settings.get(CFG_index_type), null);
		validateConfigurationStringNotEmpty(indexType, CFG_index_type);
		sourceField = XContentMapValues.nodeStringValue(settings.get(CFG_source_field), null);
		if (ValueUtils.isEmpty(sourceField)) {
			sourceField = null;
			sourceValuePattern = XContentMapValues.nodeStringValue(settings.get(CFG_source_value), null);
		}
		if (ValueUtils.isEmpty(sourceField) && ValueUtils.isEmpty(sourceValuePattern)) {
			throw new SettingsException("At least one of 'settings/" + CFG_source_field + "' or 'settings/"
					+ CFG_source_value + "' configuration value must be defined for '" + name + "' preprocessor");
		}
		resultMapping = (List<Map<String, String>>) settings.get(CFG_result_mapping);
		validateResultMappingConfiguration(resultMapping, CFG_result_mapping);
		idxSearchField = StructureUtils.getListOfStringValues(settings, CFG_idx_search_field);
		validateConfigurationObjectNotEmpty(idxSearchField, CFG_idx_search_field);
		ignoreMultipleResults = XContentMapValues.nodeBooleanValue(settings.get(CFG_ignore_multiple_results), false);
	}

	/**
	 * Validate result mapping configuration part.
	 * 
	 * @param value to check
	 * @param configFieldName name of field in preprocessor settings structure. Used for error message.
	 * @throws SettingsException thrown if value is not valid
	 */
	protected void validateResultMappingConfiguration(List<Map<String, String>> value, String configFieldName)
			throws SettingsException {
		if (value == null || value.isEmpty()) {
			throw new SettingsException("Missing or empty 'settings/" + configFieldName + "' configuration array for '"
					+ name + "' preprocessor");
		}
		for (Map<String, String> mappingRecord : value) {
			if (ValueUtils.isEmpty(mappingRecord.get(CFG_idx_result_field))) {
				throw new SettingsException("Missing or empty 'settings/" + configFieldName + "/" + CFG_idx_result_field
						+ "' configuration value for '" + name + "' preprocessor");
			}
			if (ValueUtils.isEmpty(mappingRecord.get(CFG_target_field))) {
				throw new SettingsException("Missing or empty 'settings/" + configFieldName + "/" + CFG_target_field
						+ "' configuration value for '" + name + "' preprocessor");
			}
		}
	}

	@Override
	protected LookupContenxt createContext(Map<String, Object> data) {
		return new LookupContenxt();
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void processOneSourceValue(Map<String, Object> data, LookupContenxt context, String base,
			PreprocessChainContext chainContext) {
		Object sourceValue = null;
		if (sourceField != null) {
			sourceValue = XContentMapValues.extractValue(sourceField, data);
		} else {
			sourceValue = ValueUtils.processStringValuePatternReplacement(sourceValuePattern, data, null);
		}
		Map<String, Object> targetValues = null;
		if (sourceValue instanceof Collection) {
			if (context == null)
				context = new LookupContenxt();
			Collection<Object> sourceCollection = (Collection<Object>) sourceValue;
			targetValues = new HashMap<String, Object>();
			for (Object sourceObject : sourceCollection) {
				Map<String, Object> v = lookupValue(sourceObject, data, context, chainContext);
				if (v != null) {
					for (String targetField : v.keySet()) {
						Object vo = v.get(targetField);
						if (vo != null) {
							List<Object> colTarget = (List<Object>) targetValues.get(targetField);
							if (colTarget == null) {
								colTarget = new ArrayList<Object>();
								targetValues.put(targetField, colTarget);
							}
							colTarget.add(vo);
						}
					}
				}
			}
		} else {
			targetValues = lookupValue(sourceValue, data, context, chainContext);
		}
		if (targetValues != null) {
			for (String targetField : targetValues.keySet())
				StructureUtils.putValueIntoMapOfMaps(data, targetField, targetValues.get(targetField));
		}
	}

	private boolean esExceptionWarned = false;

	/**
	 * Perform lookup for one value in ES with default handling.
	 * 
	 * @param sourceValue to be looked up
	 * @param data used in default pattern evaluation
	 * @param context
	 * @param chainContext
	 * @return Map with looked up values (defaults handled already) and target_field names as keys
	 */
	protected Map<String, Object> lookupValue(Object sourceValue, Map<String, Object> data, LookupContenxt context,
			PreprocessChainContext chainContext) {
		Map<String, Object> value = new HashMap<String, Object>();

		if (sourceValue != null) {

			if (context != null && context.lookupCache.containsKey(sourceValue))
				return context.lookupCache.get(sourceValue);

			boolean found = false;
			for (String idxSf : idxSearchField) {

				try {
					SearchRequestBuilder req = client.prepareSearch(indexName).setTypes(indexType)
							.setQuery(QueryBuilders.matchAllQuery())
							.setPostFilter(FilterBuilders.queryFilter(QueryBuilders.matchQuery(idxSf, sourceValue)));
					for (Map<String, String> mappingRecord : resultMapping) {
						String idx_resultField = mappingRecord.get(CFG_idx_result_field);
						if (idx_resultField != null && !"_source".equals(idx_resultField)) {
							req.addField(mappingRecord.get(CFG_idx_result_field));
						}
					}

					SearchResponse resp = req.execute().actionGet();

					if (resp.getHits().getTotalHits() > 0) {
						if (resp.getHits().getTotalHits() > 1) {
							String message = "More results found during lookup for value '" + sourceValue + "' using index field '"
									+ idxSf;
							if (ignoreMultipleResults)
								message += "', so we ignore them.";
							else
								message += "', so first one is used.";

							addDataWarning(chainContext, message);
							logger.debug(message);
							if (ignoreMultipleResults) {
								continue;
							}
						}
						SearchHit hit = resp.getHits().hits()[0];
						for (Map<String, String> mappingRecord : resultMapping) {
							String idx_resultField = mappingRecord.get(CFG_idx_result_field);
							Object v = null;
							SearchHitField shf = null;
							if ("_source".equals(idx_resultField)) {
								v = hit.getSource();
							} else {
								shf = hit.field(idx_resultField);
								if (shf != null) {
									v = shf.getValue();
								}
							}
							if (shf != null || v != null) {
								if (v == null && mappingRecord.get(CFG_value_default) != null) {
									v = ValueUtils.processStringValuePatternReplacement(mappingRecord.get(CFG_value_default), data,
											sourceValue);
								}
								value.put(mappingRecord.get(CFG_target_field), v);
							} else {
								String message = "Result found during lookup for value '" + sourceValue + "' using index field '"
										+ idxSf + ", but result field '" + mappingRecord.get(CFG_idx_result_field)
										+ "' is not present there";
								addDataWarning(chainContext, message);
								logger.debug(message);
							}
						}
						found = true;
					} else {
						addDataWarning(chainContext, "No result found during lookup for value '" + sourceValue + "'.");
					}

					esExceptionWarned = false;
				} catch (ElasticsearchException e) {
					if (!esExceptionWarned) {
						esExceptionWarned = true;
						String message = "Lookup failed due '" + e.getClass().getName() + ":" + e.getMessage()
								+ "', so default value handling is used.";
						addDataWarning(chainContext, message);
						logger.warn(message);
					}
				}
			}
			if (!found) {
				processDefaultValues(sourceValue, data, value, chainContext);
			}
		}

		if (context != null && sourceValue != null)
			context.lookupCache.put(sourceValue, value);

		return value;
	}

	private void processDefaultValues(Object sourceValue, Map<String, Object> data, Map<String, Object> value,
			PreprocessChainContext chainContext) {

		for (Map<String, String> mappingRecord : resultMapping) {
			if (mappingRecord.get(CFG_value_default) != null) {
				Object v = ValueUtils.processStringValuePatternReplacement(mappingRecord.get(CFG_value_default), data,
						sourceValue);
				value.put(mappingRecord.get(CFG_target_field), v);
			} else {
				value.put(mappingRecord.get(CFG_target_field), null);
			}
		}
	}

	protected class LookupContenxt {
		Map<Object, Map<String, Object>> lookupCache = new HashMap<Object, Map<String, Object>>();
	}

	public List<String> getSourceBases() {
		return sourceBases;
	}

	public String getIndexName() {
		return indexName;
	}

	public String getIndexType() {
		return indexType;
	}

	public String getSourceField() {
		return sourceField;
	}

	public String getSourceValuePattern() {
		return sourceValuePattern;
	}

	public List<String> getIdxSearchField() {
		return idxSearchField;
	}

	public List<Map<String, String>> getResultMapping() {
		return resultMapping;
	}

}
