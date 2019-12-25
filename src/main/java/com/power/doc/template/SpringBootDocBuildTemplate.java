package com.power.doc.template;

import com.power.common.util.JsonFormatUtil;
import com.power.common.util.RandomUtil;
import com.power.common.util.StringUtil;
import com.power.common.util.UrlUtil;
import com.power.doc.builder.ProjectDocConfigBuilder;
import com.power.doc.constants.*;
import com.power.doc.handler.SpringMVCRequestHeaderHandler;
import com.power.doc.handler.SpringMVCRequestMappingHandler;
import com.power.doc.helper.FormDataBuildHelper;
import com.power.doc.helper.JsonBuildHelper;
import com.power.doc.helper.ParamsBuildHelper;
import com.power.doc.model.*;
import com.power.doc.model.request.ApiRequestExample;
import com.power.doc.model.request.RequestMapping;
import com.power.doc.utils.DocClassUtil;
import com.power.doc.utils.DocUtil;
import com.power.doc.utils.JavaClassUtil;
import com.power.doc.utils.JavaClassValidateUtil;
import com.thoughtworks.qdox.model.*;
import com.thoughtworks.qdox.model.expression.AnnotationValue;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.power.doc.constants.DocGlobalConstants.FILE_CONTENT_TYPE;
import static com.power.doc.constants.DocGlobalConstants.JSON_CONTENT_TYPE;
import static com.power.doc.constants.DocTags.IGNORE;

/**
 * @author yu 2019/12/21.
 */
public class SpringBootDocBuildTemplate implements IDocBuildTemplate {

    private List<ApiReqHeader> headers;

    @Override
    public List<ApiDoc> getApiData(ProjectDocConfigBuilder projectBuilder) {
        ApiConfig apiConfig = projectBuilder.getApiConfig();
        this.headers = apiConfig.getRequestHeaders();
        List<ApiDoc> apiDocList = new ArrayList<>();
        int order = 0;
        for (JavaClass cls : projectBuilder.getJavaProjectBuilder().getClasses()) {
            if (!checkController(cls)) {
                continue;
            }
            if (StringUtil.isNotEmpty(apiConfig.getPackageFilters())) {
                if (DocUtil.isMatch(apiConfig.getPackageFilters(), cls.getCanonicalName())) {
                    order++;
                    List<ApiMethodDoc> apiMethodDocs = buildControllerMethod(cls, apiConfig, projectBuilder);
                    this.handleApiDoc(cls, apiDocList, apiMethodDocs, order, apiConfig.isMd5EncryptedHtmlName());
                }
            } else {
                order++;
                List<ApiMethodDoc> apiMethodDocs = buildControllerMethod(cls, apiConfig, projectBuilder);
                this.handleApiDoc(cls, apiDocList, apiMethodDocs, order, apiConfig.isMd5EncryptedHtmlName());
            }
        }
        return apiDocList;
    }

    @Override
    public boolean ignoreReturnObject(String typeName) {
        if (JavaClassValidateUtil.isMvcIgnoreParams(typeName)) {
            if (DocGlobalConstants.MODE_AND_VIEW_FULLY.equals(typeName)) {
                return true;
            }
        }
        return false;
    }

    private List<ApiMethodDoc> buildControllerMethod(final JavaClass cls, ApiConfig apiConfig, ProjectDocConfigBuilder projectBuilder) {
        String clazName = cls.getCanonicalName();
        List<JavaAnnotation> classAnnotations = cls.getAnnotations();
        String baseUrl = "";
        for (JavaAnnotation annotation : classAnnotations) {
            String annotationName = annotation.getType().getName();
            if (DocAnnotationConstants.REQUEST_MAPPING.equals(annotationName) || DocGlobalConstants.REQUEST_MAPPING_FULLY.equals(annotationName)) {
                baseUrl = StringUtil.removeQuotes(annotation.getNamedParameter("value").toString());
            }
        }
        List<JavaMethod> methods = cls.getMethods();
        List<ApiMethodDoc> methodDocList = new ArrayList<>(methods.size());
        int methodOrder = 0;
        for (JavaMethod method : methods) {
            if (method.getModifiers().contains("private")) {
                continue;
            }
            if (StringUtil.isEmpty(method.getComment()) && apiConfig.isStrict()) {
                throw new RuntimeException("Unable to find comment for method " + method.getName() + " in " + cls.getCanonicalName());
            }
            methodOrder++;
            ApiMethodDoc apiMethodDoc = new ApiMethodDoc();
            apiMethodDoc.setOrder(methodOrder);
            apiMethodDoc.setDesc(method.getComment());
            apiMethodDoc.setName(method.getName());
            String methodUid = DocUtil.handleId(clazName + method.getName());
            apiMethodDoc.setMethodId(methodUid);
            String apiNoteValue = DocUtil.getNormalTagComments(method, DocTags.API_NOTE, cls.getName());
            if (StringUtil.isEmpty(apiNoteValue)) {
                apiNoteValue = method.getComment();
            }
            String authorValue = DocUtil.getNormalTagComments(method, DocTags.AUTHOR, cls.getName());
            if (apiConfig.isShowAuthor() && StringUtil.isNotEmpty(authorValue)) {
                apiMethodDoc.setAuthor(authorValue);
            }
            apiMethodDoc.setDetail(apiNoteValue);
            //handle request mapping
            RequestMapping requestMapping = new SpringMVCRequestMappingHandler()
                    .handle(projectBuilder.getServerUrl(), baseUrl, method);
            //handle headers
            List<ApiReqHeader> apiReqHeaders = new SpringMVCRequestHeaderHandler().handle(method);
            apiMethodDoc.setRequestHeaders(apiReqHeaders);
            if (Objects.nonNull(requestMapping)) {
                if (null != method.getTagByName(IGNORE)) {
                    continue;
                }
                apiMethodDoc.setType(requestMapping.getMethodType());
                apiMethodDoc.setUrl(requestMapping.getUrl());
                apiMethodDoc.setServerUrl(projectBuilder.getServerUrl());
                apiMethodDoc.setPath(requestMapping.getShortUrl());
                // build request params
                List<ApiParam> requestParams = requestParams(method, DocTags.PARAM, projectBuilder);
                apiMethodDoc.setRequestParams(requestParams);
                // build request json
                ApiRequestExample requestExample = buildReqJson(method, apiMethodDoc, requestMapping.isPostMethod(), projectBuilder);
                String requestJson = requestExample.getExampleBody();
                // set request example detail
                apiMethodDoc.setRequestExample(requestExample);
                apiMethodDoc.setRequestUsage(requestJson);
                // build response usage
                apiMethodDoc.setResponseUsage(JsonBuildHelper.buildReturnJson(method, projectBuilder));
                // build response params
                List<ApiParam> responseParams = buildReturnApiParams(method, cls.getGenericFullyQualifiedName(), projectBuilder);
                apiMethodDoc.setResponseParams(responseParams);
                List<ApiReqHeader> allApiReqHeaders;
                if (this.headers != null) {
                    allApiReqHeaders = Stream.of(this.headers, apiReqHeaders)
                            .flatMap(Collection::stream).distinct().collect(Collectors.toList());
                } else {
                    allApiReqHeaders = apiReqHeaders;
                }
                //reduce create in template
                apiMethodDoc.setHeaders(this.createDocRenderHeaders(allApiReqHeaders, apiConfig.isAdoc()));
                apiMethodDoc.setRequestHeaders(allApiReqHeaders);
                methodDocList.add(apiMethodDoc);
            }
        }
        return methodDocList;
    }

    private ApiRequestExample buildReqJson(JavaMethod method, ApiMethodDoc apiMethodDoc, Boolean isPostMethod, ProjectDocConfigBuilder configBuilder) {
        List<JavaParameter> parameterList = method.getParameters();
        if (parameterList.size() < 1) {
            return ApiRequestExample.builder().setJsonBody(apiMethodDoc.getUrl());
        }
        Map<String, String> pathParamsMap = new LinkedHashMap<>();
        Map<String, String> paramsComments = DocUtil.getParamsComments(method, DocTags.PARAM, null);
        List<String> springMvcRequestAnnotations = SpringMvcRequestAnnotationsEnum.listSpringMvcRequestAnnotations();
        List<FormData> formDataList = new ArrayList<>();
        ApiRequestExample requestExample = ApiRequestExample.builder();
        for (JavaParameter parameter : parameterList) {
            JavaType javaType = parameter.getType();
            String simpleTypeName = javaType.getValue();
            String gicTypeName = javaType.getGenericCanonicalName();
            String typeName = javaType.getFullyQualifiedName();
            JavaClass javaClass = configBuilder.getJavaProjectBuilder().getClassByName(typeName);
            String[] globGicName = DocClassUtil.getSimpleGicName(gicTypeName);
            String paramName = parameter.getName();
            if (JavaClassValidateUtil.isMvcIgnoreParams(typeName)) {
                continue;
            }
            String comment = this.paramCommentResolve(paramsComments.get(paramName));
            String mockValue = "";
            if (JavaClassValidateUtil.isPrimitive(simpleTypeName)) {
                mockValue = paramsComments.get(paramName);
                if (Objects.nonNull(mockValue) && mockValue.contains("|")) {
                    mockValue = mockValue.substring(mockValue.lastIndexOf("|") + 1, mockValue.length());
                } else {
                    mockValue = "";
                }
                if (StringUtil.isEmpty(mockValue)) {
                    mockValue = DocUtil.getValByTypeAndFieldName(simpleTypeName, paramName, Boolean.TRUE);
                }
            }
            List<JavaAnnotation> annotations = parameter.getAnnotations();
            boolean paramAdded = false;
            for (JavaAnnotation annotation : annotations) {
                String annotationName = annotation.getType().getSimpleName();
                String fullName = annotation.getType().getSimpleName();
                if (!springMvcRequestAnnotations.contains(fullName) || paramAdded) {
                    continue;
                }
                if (SpringMvcAnnotations.REQUEST_HERDER.equals(annotationName)) {
                    continue;
                }
                AnnotationValue annotationDefaultVal = annotation.getProperty(DocAnnotationConstants.DEFAULT_VALUE_PROP);
                if (null != annotationDefaultVal) {
                    mockValue = StringUtil.removeQuotes(annotationDefaultVal.toString());
                }
                AnnotationValue annotationValue = annotation.getProperty(DocAnnotationConstants.VALUE_PROP);
                if (null != annotationValue) {
                    paramName = StringUtil.removeQuotes(annotationValue.toString());
                }
                AnnotationValue annotationOfName = annotation.getProperty(DocAnnotationConstants.NAME_PROP);
                if (null != annotationOfName) {
                    paramName = StringUtil.removeQuotes(annotationOfName.toString());
                }
                if (SpringMvcAnnotations.REQUEST_BODY.equals(annotationName) || DocGlobalConstants.REQUEST_BODY_FULLY.equals(annotationName)) {
                    apiMethodDoc.setContentType(JSON_CONTENT_TYPE);
                    if (JavaClassValidateUtil.isPrimitive(simpleTypeName)) {
                        StringBuilder builder = new StringBuilder();
                        builder.append("{\"")
                                .append(paramName)
                                .append("\":")
                                .append(DocUtil.handleJsonStr(mockValue))
                                .append("}");
                        requestExample.setJsonBody(builder.toString()).setJson(true);
                        paramAdded = true;
                    } else {
                        String json = JsonBuildHelper.buildJson(typeName, gicTypeName, Boolean.FALSE, 0, new HashMap<>(), configBuilder);
                        requestExample.setJsonBody(json).setJson(true);
                        paramAdded = true;
                    }
                } else if (SpringMvcAnnotations.PATH_VARIABLE.contains(annotationName)) {
                    if (javaClass.isEnum()) {
                        Object value = JavaClassUtil.getEnumValue(javaClass, Boolean.TRUE);
                        mockValue = StringUtil.removeQuotes(String.valueOf(value));
                    }
                    pathParamsMap.put(paramName, mockValue);
                    paramAdded = true;
                }
            }
            if (paramAdded) {
                continue;
            }
            //file upload
            if (gicTypeName.contains(DocGlobalConstants.MULTIPART_FILE_FULLY)) {
                apiMethodDoc.setContentType(FILE_CONTENT_TYPE);
                FormData formData = new FormData();
                formData.setKey(paramName);
                formData.setType("file");
                formData.setDesc(comment);
                formData.setValue(mockValue);
                formDataList.add(formData);
            } else if (JavaClassValidateUtil.isPrimitive(typeName)) {
                FormData formData = new FormData();
                formData.setKey(paramName);
                formData.setDesc(comment);
                formData.setType("text");
                formData.setValue(mockValue);
                formDataList.add(formData);
            } else if (JavaClassValidateUtil.isArray(typeName) || JavaClassValidateUtil.isCollection(typeName)) {
                String gicName = globGicName[0];
                if (JavaClassValidateUtil.isArray(gicName)) {
                    gicName = gicName.substring(0, gicName.indexOf("["));
                }
                if (!JavaClassValidateUtil.isPrimitive(gicName)) {
                    throw new RuntimeException("FormData can't support binding Collection<T> on method "
                            + method.getName() + "Check it in " + method.getDeclaringClass().getCanonicalName());
                }
                FormData formData = new FormData();
                formData.setKey(paramName);
                if (!paramName.contains("[]")) {
                    formData.setKey(paramName + "[]");
                }
                formData.setDesc(comment);
                formData.setType("text");
                formData.setValue(RandomUtil.randomValueByType(gicName));
                formDataList.add(formData);
            } else if (javaClass.isEnum()) {
                // do nothing
                Object value = JavaClassUtil.getEnumValue(javaClass, Boolean.TRUE);
                String strVal = StringUtil.removeQuotes(String.valueOf(value));
                FormData formData = new FormData();
                formData.setKey(paramName);
                formData.setType("text");
                formData.setDesc(comment);
                formData.setValue(strVal);
                formDataList.add(formData);
            } else {
                formDataList.addAll(FormDataBuildHelper.getFormData(gicTypeName, new HashMap<>(), 0, configBuilder, DocGlobalConstants.ENMPTY));
            }
        }
        requestExample.setFormDataList(formDataList);
        String[] paths = apiMethodDoc.getPath().split(";");
        String path = paths[0];
        String body;
        String exampleBody;
        String url;

        if (isPostMethod) {
            //for post
            path = DocUtil.formatAndRemove(path, pathParamsMap);
            body = UrlUtil.urlJoin(DocGlobalConstants.ENMPTY, DocUtil.formDataToMap(formDataList)).replace("?", DocGlobalConstants.ENMPTY);
            body = StringUtil.removeQuotes(body);
            url = apiMethodDoc.getServerUrl() + "/" + path;
            url = UrlUtil.simplifyUrl(url);
            if (requestExample.isJson()) {
                if (StringUtil.isNotEmpty(requestExample.getJsonBody())) {
                    exampleBody = DocGlobalConstants.CURL_POST_JSON + url + " --data \'" + JsonFormatUtil.formatJson(requestExample.getJsonBody()) + "\n'";
                } else {
                    exampleBody = DocGlobalConstants.CURL_POST + url;
                }
            } else {
                if (StringUtil.isNotEmpty(body)) {
                    exampleBody = DocGlobalConstants.CURL_POST + url + " --data \'" + body + "'";
                } else {
                    exampleBody = DocGlobalConstants.CURL_POST + url;
                }
            }
            requestExample.setExampleBody(exampleBody).setJsonBody(body).setUrl(url);
        } else {
            // for get
            pathParamsMap.putAll(DocUtil.formDataToMap(formDataList));
            path = DocUtil.formatAndRemove(path, pathParamsMap);
            url = UrlUtil.urlJoin(path, pathParamsMap);
            url = StringUtil.removeQuotes(url);
            url = apiMethodDoc.getServerUrl() + "/" + url;
            url = UrlUtil.simplifyUrl(url);
            exampleBody = "curl -X GET -i \'" + url + "\'";
            requestExample.setExampleBody(exampleBody).setJsonBody("").setUrl(url);
        }
        return requestExample;
    }

    private List<ApiParam> requestParams(final JavaMethod javaMethod, final String tagName, ProjectDocConfigBuilder builder) {

        boolean isStrict = builder.getApiConfig().isStrict();
        Map<String, CustomRespField> responseFieldMap = new HashMap<>();
        String className = javaMethod.getDeclaringClass().getCanonicalName();
        Map<String, String> paramTagMap = DocUtil.getParamsComments(javaMethod, tagName, className);
        List<JavaParameter> parameterList = javaMethod.getParameters();
        if (parameterList.size() < 1) {
            return null;
        }
        List<ApiParam> paramList = new ArrayList<>();
        int requestBodyCounter = 0;
        List<ApiParam> reqBodyParamsList = new ArrayList<>();
        out:
        for (JavaParameter parameter : parameterList) {
            boolean paramAdded = false;
            String paramName = parameter.getName();
            String typeName = parameter.getType().getGenericCanonicalName();
            String simpleName = parameter.getType().getValue().toLowerCase();
            String fullTypeName = parameter.getType().getFullyQualifiedName();
            if (JavaClassValidateUtil.isMvcIgnoreParams(typeName)) {
                continue out;
            }
            if (!paramTagMap.containsKey(paramName) && JavaClassValidateUtil.isPrimitive(fullTypeName) && isStrict) {
                throw new RuntimeException("ERROR: Unable to find javadoc @param for actual param \""
                        + paramName + "\" in method " + javaMethod.getName() + " from " + className);
            }
            JavaClass javaClass = builder.getJavaProjectBuilder().getClassByName(fullTypeName);
            String comment = this.paramCommentResolve(paramTagMap.get(paramName));
            //file upload
            if (typeName.contains(DocGlobalConstants.MULTIPART_FILE_FULLY)) {
                ApiParam param = ApiParam.of().setField(paramName).setType("file")
                        .setDesc(comment).setRequired(true).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                paramList.add(param);
                continue out;
            }
            List<JavaAnnotation> annotations = parameter.getAnnotations();
            if (annotations.size() == 0) {
                //default set required is true
                if (JavaClassValidateUtil.isCollection(fullTypeName) || JavaClassValidateUtil.isArray(fullTypeName)) {
                    String[] gicNameArr = DocClassUtil.getSimpleGicName(typeName);
                    String gicName = gicNameArr[0];
                    if (JavaClassValidateUtil.isArray(gicName)) {
                        gicName = gicName.substring(0, gicName.indexOf("["));
                    }
                    String typeTemp = "";
                    if (JavaClassValidateUtil.isPrimitive(gicName)) {
                        typeTemp = " of " + DocClassUtil.processTypeNameForParams(gicName);
                        ApiParam param = ApiParam.of().setField(paramName)
                                .setType(DocClassUtil.processTypeNameForParams(simpleName) + typeTemp)
                                .setDesc(comment).setRequired(true).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                        paramList.add(param);
                    } else {
                        ApiParam param = ApiParam.of().setField(paramName)
                                .setType(DocClassUtil.processTypeNameForParams(simpleName) + typeTemp)
                                .setDesc(comment).setRequired(true).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                        paramList.add(param);
                        paramList.addAll(ParamsBuildHelper.buildParams(gicNameArr[0], "└─", 1, "true", responseFieldMap, Boolean.FALSE, new HashMap<>(), builder));
                    }
                } else if (JavaClassValidateUtil.isPrimitive(simpleName)) {
                    ApiParam param = ApiParam.of().setField(paramName)
                            .setType(DocClassUtil.processTypeNameForParams(simpleName))
                            .setDesc(comment).setRequired(true).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                    paramList.add(param);
                } else if (DocGlobalConstants.JAVA_MAP_FULLY.equals(typeName)) {
                    ApiParam param = ApiParam.of().setField(paramName)
                            .setType("map").setDesc(comment).setRequired(true).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                    paramList.add(param);
                } else if (javaClass.isEnum()) {
                    ApiParam param = ApiParam.of().setField(paramName)
                            .setType("string").setDesc(comment).setRequired(true).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                    paramList.add(param);
                } else {
                    paramList.addAll(ParamsBuildHelper.buildParams(fullTypeName, DocGlobalConstants.ENMPTY, 0, "true", responseFieldMap, Boolean.FALSE, new HashMap<>(), builder));
                }
            }
            for (JavaAnnotation annotation : annotations) {
                String required = "true";
                AnnotationValue annotationRequired = annotation.getProperty(DocAnnotationConstants.REQUIRED_PROP);
                if (null != annotationRequired) {
                    required = annotationRequired.toString();
                }
                String annotationName = annotation.getType().getName();
                if (SpringMvcAnnotations.REQUEST_BODY.equals(annotationName) || (ValidatorAnnotations.VALID.equals(annotationName) && annotations.size() == 1)) {
                    if (requestBodyCounter > 0) {
                        throw new RuntimeException("You have use @RequestBody Passing multiple variables  for method "
                                + javaMethod.getName() + " in " + className + ",@RequestBody annotation could only bind one variables.");
                    }
                    if (JavaClassValidateUtil.isPrimitive(fullTypeName)) {
                        ApiParam bodyParam = ApiParam.of()
                                .setField(paramName).setType(DocClassUtil.processTypeNameForParams(simpleName))
                                .setDesc(comment).setRequired(Boolean.valueOf(required));
                        reqBodyParamsList.add(bodyParam);
                    } else {
                        if (JavaClassValidateUtil.isCollection(fullTypeName) || JavaClassValidateUtil.isArray(fullTypeName)) {
                            String[] gicNameArr = DocClassUtil.getSimpleGicName(typeName);
                            String gicName = gicNameArr[0];
                            if (JavaClassValidateUtil.isArray(gicName)) {
                                gicName = gicName.substring(0, gicName.indexOf("["));
                            }
                            if (JavaClassValidateUtil.isPrimitive(gicName)) {
                                ApiParam bodyParam = ApiParam.of()
                                        .setField(paramName).setType(DocClassUtil.processTypeNameForParams(simpleName))
                                        .setDesc(comment).setRequired(Boolean.valueOf(required)).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                                reqBodyParamsList.add(bodyParam);
                            } else {
                                reqBodyParamsList.addAll(ParamsBuildHelper.buildParams(gicNameArr[0], DocGlobalConstants.ENMPTY, 0, "true", responseFieldMap, Boolean.FALSE, new HashMap<>(), builder));
                            }

                        } else if (JavaClassValidateUtil.isMap(fullTypeName)) {
                            if (DocGlobalConstants.JAVA_MAP_FULLY.equals(typeName)) {
                                ApiParam apiParam = ApiParam.of().setField(paramName).setType("map")
                                        .setDesc(comment).setRequired(Boolean.valueOf(required)).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                                paramList.add(apiParam);
                                continue out;
                            }
                            String[] gicNameArr = DocClassUtil.getSimpleGicName(typeName);
                            reqBodyParamsList.addAll(ParamsBuildHelper.buildParams(gicNameArr[1], DocGlobalConstants.ENMPTY, 0, "true", responseFieldMap, Boolean.FALSE, new HashMap<>(), builder));
                        } else {
                            reqBodyParamsList.addAll(ParamsBuildHelper.buildParams(typeName, DocGlobalConstants.ENMPTY, 0, "true", responseFieldMap, Boolean.FALSE, new HashMap<>(), builder));
                        }
                    }
                    requestBodyCounter++;
                } else {
                    if (paramAdded) {
                        continue;
                    }
                    List<String> validatorAnnotations = DocValidatorAnnotationEnum.listValidatorAnnotations();
                    if (SpringMvcAnnotations.REQUEST_PARAM.equals(annotationName) ||
                            DocAnnotationConstants.SHORT_PATH_VARIABLE.equals(annotationName)) {
                        AnnotationValue annotationValue = annotation.getProperty(DocAnnotationConstants.VALUE_PROP);
                        if (null != annotationValue) {
                            paramName = StringUtil.removeQuotes(annotationValue.toString());
                        }
                        AnnotationValue annotationOfName = annotation.getProperty(DocAnnotationConstants.NAME_PROP);
                        if (null != annotationOfName) {
                            paramName = StringUtil.removeQuotes(annotationOfName.toString());
                        }
                        ApiParam param = ApiParam.of().setField(paramName)
                                .setType(DocClassUtil.processTypeNameForParams(simpleName))
                                .setDesc(comment).setRequired(Boolean.valueOf(required)).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                        paramList.add(param);
                        paramAdded = true;
                    } else if (validatorAnnotations.contains(annotationName)) {
                        ApiParam param = ApiParam.of().setField(paramName)
                                .setType(DocClassUtil.processTypeNameForParams(simpleName))
                                .setDesc(comment).setRequired(Boolean.valueOf(required)).setVersion(DocGlobalConstants.DEFAULT_VERSION);
                        paramList.add(param);
                        paramAdded = true;
                    } else {
                        continue;
                    }
                }
            }

        }
        if (requestBodyCounter > 0) {
            paramList.addAll(reqBodyParamsList);
            return paramList;
        }
        return paramList;
    }

    private boolean checkController(JavaClass cls) {
        List<JavaAnnotation> classAnnotations = cls.getAnnotations();
        for (JavaAnnotation annotation : classAnnotations) {
            String name = annotation.getType().getName();
            name = JavaClassUtil.getAnnotationSimpleName(name);
            if (SpringMvcAnnotations.CONTROLLER.equals(name) || SpringMvcAnnotations.REST_CONTROLLER.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
