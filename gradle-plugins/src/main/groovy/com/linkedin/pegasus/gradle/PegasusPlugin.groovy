/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.pegasus.gradle


import org.gradle.BuildResult
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin

/**
 * Pegasus code generation plugin.
 * The supported project layout for this plugin is as follows:
 *
 * <pre>
 *   --- api/
 *   |   --- build.gradle
 *   |   --- src/
 *   |       --- &lt;sourceSet&gt;/
 *   |       |   --- idl/
 *   |       |   |   --- &lt;published idl (.restspec.json) files&gt;
 *   |       |   --- java/
 *   |       |   |   --- &lt;packageName&gt;/
 *   |       |   |       --- &lt;common java files&gt;
 *   |       |   --- pegasus/
 *   |       |       --- &lt;packageName&gt;/
 *   |       |           --- &lt;data schema (.pdsc) files&gt;
 *   |       --- &lt;sourceSet&gt;GeneratedDataTemplate/
 *   |       |   --- java/
 *   |       |       --- &lt;packageName&gt;/
 *   |       |           --- &lt;data template source files generated from data schema (.pdsc) files&gt;
 *   |       --- &lt;sourceSet&gt;GeneratedAvroSchema/
 *   |       |   --- avro/
 *   |       |       --- &lt;packageName&gt;/
 *   |       |           --- &lt;avsc avro schema files (.avsc) generated from pegasus schema files&gt;
 *   |       --- &lt;sourceSet&gt;GeneratedRest/
 *   |           --- java/
 *   |               --- &lt;packageName&gt;/
 *   |                   --- &lt;rest client source (.java) files generated from published idl&gt;
 *   --- impl/
 *   |   --- build.gradle
 *   |   --- src/
 *   |       --- &lt;sourceSet&gt;/
 *   |       |   --- java/
 *   |       |       --- &lt;packageName&gt;/
 *   |       |           --- &lt;resource class source (.java) files&gt;
 *   |       --- &lt;sourceSet&gt;GeneratedRest/
 *   |           --- idl/
 *   |               --- &lt;generated idl (.restspec.json) files&gt;
 *   --- &lt;other projects&gt;/
 * </pre>
 * <ul>
 *   <li>
 *    <i>api</i>: contains all the files which are commonly depended by the server and
 *    client implementation. The common files include the data schema (.pdsc) files,
 *    the idl (.restspec.json) files and potentially Java interface files used by both sides.
 *  </li>
 *  <li>
 *    <i>impl</i>: contains the resource class for server implementation.
 *  </li>
 * </ul>
 * <p>Performs the following functions:</p>
 *
 * <p><b>Generate data model and data template jars for each source set.</b></p>
 *
 * <p><i>Overview:</i></p>
 *
 * <p>
 * In the api project, the plugin generates the data template source (.java) files from the
 * data schema (.pdsc) files, and furthermore compiles the source files and packages them
 * to jar files. Details of jar contents will be explained in following paragraphs.
 * In general, data schema files should exist only in api projects.
 * </p>
 *
 * <p>
 * Configure the server and client implementation projects to depend on the
 * api project's dataTemplate configuration to get access to the generated data templates
 * from within these projects. This allows api classes to be built first so that implementation
 * projects can consume them. We recommend this structure to avoid circular dependencies
 * (directly or indirectly) among implementation projects.
 * </p>
 *
 * <p><i>Detail:</i></p>
 *
 * <p>
 * Generates data template source (.java) files from data schema (.pdsc) files,
 * compiles the data template source (.java) files into class (.class) files,
 * creates a data model jar file and a data template jar file.
 * The data model jar file contains the source data schema (.pdsc) files.
 * The data template jar file contains both the source data schema (.pdsc) files
 * and the generated data template class (.class) files.
 * </p>
 *
 * <p>
 * In the data template generation phase, the plugin creates a new target source set
 * for the generated files. The new target source set's name is the input source set name's
 * suffixed with "GeneratedDataTemplate", e.g. "mainGeneratedDataTemplate".
 * The plugin invokes PegasusDataTemplateGenerator to generate data template source (.java) files
 * for all data schema (.pdsc) files present in the input source set's pegasus
 * directory, e.g. "src/main/pegasus". The generated data template source (.java) files
 * will be in the new target source set's java source directory, e.g.
 * "src/mainGeneratedDataTemplate/java". In addition to
 * the data schema (.pdsc) files in the pegasus directory, the dataModel configuration
 * specifies resolver path for the PegasusDataTemplateGenerator. The resolver path
 * provides the data schemas and previously generated data template classes that
 * may be referenced by the input source set's data schemas. In most cases, the dataModel
 * configuration should contain data template jars.
 * </p>
 *
 * <p>
 * The next phase is the data template compilation phase, the plugin compiles the generated
 * data template source (.java) files into class files. The dataTemplateCompile configuration
 * specifies the pegasus jars needed to compile these classes. The compileClasspath of the
 * target source set is a composite of the dataModel configuration which includes the data template
 * classes that were previously generated and included in the dependent data template jars,
 * and the dataTemplateCompile configuration.
 * This configuration should specify a dependency on the Pegasus data jar.
 * </p>
 *
 * <p>
 * The following phase is creating the the data model jar and the data template jar.
 * This plugin creates the data model jar that includes the contents of the
 * input source set's pegasus directory, and sets the jar file's classification to
 * "data-model". Hence, the resulting jar file's name should end with "-data-model.jar".
 * It adds the data model jar as an artifact to the dataModel configuration.
 * This jar file should only contain data schema (.pdsc) files.
 * </p>
 *
 * <p>
 * This plugin also create the data template jar that includes the contents of the input
 * source set's pegasus directory and the java class output directory of the
 * target source set. It sets the jar file's classification to "data-template".
 * Hence, the resulting jar file's name should end with "-data-template.jar".
 * It adds the data template jar file as an artifact to the dataTemplate configuration.
 * This jar file contains both data schema (.pdsc) files and generated data template
 * class (.class) files.
 * </p>
 *
 * <p>
 * This plugin will ensure that data template source files are generated before
 * compiling the input source set and before the idea and eclipse tasks. It
 * also adds the generated classes to the compileClasspath of the input source set.
 * </p>
 *
 * <p>
 * The configurations that apply to generating the data model and data template jars
 * are as follow:
 * <ul>
 *   <li>
 *     The dataTemplateCompile configuration specifies the classpath for compiling
 *     the generated data template source (.java) files. In most cases,
 *     it should be the Pegasus data jar.
 *     (The default compile configuration is not used for compiling data templates because
 *     it is not desirable to include non data template dependencies in the data template jar.)
 *     The configuration should not directly include data template jars. Data template jars
 *     should be included in the dataModel configuration.
 *   </li>
 *   <li>
 *     The dataModel configuration provides the value of the "generator.resolver.path"
 *     system property that is passed to PegasusDataTemplateGenerator. In most cases,
 *     this configuration should contain only data template jars. The data template jars
 *     contain both data schema (.pdsc) files and generated data template (.class) files.
 *     PegasusDataTemplateGenerator will not generate data template (.java) files for
 *     classes that can be found in the resolver path. This avoids redundant generation
 *     of the same classes, and inclusion of these classes in multiple jars.
 *     The dataModel configuration is also used to publish the data model jar which
 *     contains only data schema (.pdsc) files.
 *   </li>
 *   <li>
 *     The testDataModel configuration is similar to the dataModel configuration
 *     except it is used when generating data templates from test source sets.
 *     It extends from the dataModel configuration. It is also used to publish
 *     the data model jar from test source sets.
 *   </li>
 *   <li>
 *     The dataTemplate configuration is used to publish the data template
 *     jar which contains both data schema (.pdsc) files and the data template class
 *     (.class) files generated from these data schema (.pdsc) files.
 *   </li>
 *   <li>
 *     The testDataTemplate configuration is similar to the dataTemplate configuration
 *     except it is used when publishing the data template jar files generated from
 *     test source sets.
 *   </li>
 * </ul>
 * </p>
 *
 * <p>Performs the following functions:</p>
 *
 * <p><b>Generate avro schema jars for each source set.</b></p>
 *
 * <p><i>Overview:</i></p>
 *
 * <p>
 * In the api project, the task 'generateAvroSchema' generates the avro schema (.avsc)
 * files from pegasus schema (.pdsc) files. In general, data schema files should exist
 * only in api projects.
 * </p>
 *
 * <p>
 * Configure the server and client implementation projects to depend on the
 * api project's avroSchema configuration to get access to the generated avro schemas
 * from within these projects.
 * </p>
 *
 * <p>
 * This plugin also create the avro schema jar that includes the contents of the input
 * source set's avro directory and the avsc schema files.
 * The resulting jar file's name should end with "-avro-schema.jar".
 * </p>
 *
 * <p><b>Generate rest model and rest client jars for each source set.</b></p>
 *
 * <p><i>Overview:</i></p>
 *
 * <p>
 * In the api project, generates rest client source (.java) files from the idl,
 * compiles the rest client source (.java) files to rest client class (.class) files
 * and puts them in jar files. In general, the api project should be only place that
 * contains the publishable idl files. If the published idl changes an existing idl
 * in the api project, the plugin will emit message indicating this has occurred and
 * suggest that the entire project be rebuilt if it is desirable for clients of the
 * idl to pick up the newly published changes.
 * </p>
 *
 * <p>
 * In the impl project, generates the idl (.restspec.json) files from the input
 * source set's resource class files, then compares them against the existing idl
 * files in the api project for compatibility checking. If incompatible changes are
 * found, the build fails (unless certain flag is specified, see below). If the
 * generated idl passes compatibility checks (see compatibility check levels below),
 * publishes the generated idl (.restspec.json) to the api project.
 * </p>
 *
 * <p><i>Detail:</i></p>
 *
 * <p><b>rest client generation phase</b>: in api project</p>
 *
 * <p>
 * In this phase, the rest client source (.java) files are generated from the
 * api project idl (.restspec.json) files using RestRequestBuilderGenerator.
 * The generated rest client source files will be in the new target source set's
 * java source directory, e.g. "src/mainGeneratedRest/java".
 * </p>
 *
 * <p>
 * RestRequestBuilderGenerator requires access to the data schemas referenced
 * by the idl. The dataModel configuration specifies the resolver path needed
 * by RestRequestBuilderGenerator to access the data schemas referenced by
 * the idl that is not in the source set's pegasus directory.
 * This plugin automatically includes the data schema (.pdsc) files in the
 * source set's pegasus directory in the resolver path.
 * In most cases, the dataModel configuration should contain data template jars.
 * The data template jars contains both data schema (.pdsc) files and generated
 * data template class (.class) files. By specifying data template jars instead
 * of data model jars, redundant generation of data template classes is avoided
 * as classes that can be found in the resolver path are not generated.
 * </p>
 *
 * <p><b>rest client compilation phase</b>: in api project</p>
 *
 * <p>
 * In this phase, the plugin compiles the generated rest client source (.java)
 * files into class files. The restClientCompile configuration specifies the
 * pegasus jars needed to compile these classes. The compile classpath is a
 * composite of the dataModel configuration which includes the data template
 * classes that were previously generated and included in the dependent data template
 * jars, and the restClientCompile configuration.
 * This configuration should specify a dependency on the Pegasus restli-client jar.
 * </p>
 *
 * <p>
 * The following stage is creating the the rest model jar and the rest client jar.
 * This plugin creates the rest model jar that includes the
 * generated idl (.restspec.json) files, and sets the jar file's classification to
 * "rest-model". Hence, the resulting jar file's name should end with "-rest-model.jar".
 * It adds the rest model jar as an artifact to the restModel configuration.
 * This jar file should only contain idl (.restspec.json) files.
 * </p>
 *
 * <p>
 * This plugin also create the rest client jar that includes the generated
 * idl (.restspec.json) files and the java class output directory of the
 * target source set. It sets the jar file's classification to "rest-client".
 * Hence, the resulting jar file's name should end with "-rest-client.jar".
 * It adds the rest client jar file as an artifact to the restClient configuration.
 * This jar file contains both idl (.restspec.json) files and generated rest client
 * class (.class) files.
 * </p>
 *
 * <p><b>idl generation phase</b>: in server implementation project</p>
 *
 * <p>
 * Before entering this phase, the plugin will ensure that generating idl will
 * occur after compiling the input source set. It will also ensure that IDEA
 * and Eclipse tasks runs after  rest client source (.java) files are generated.
 * </p>
 *
 * <p>
 * In this phase, the plugin creates a new target source set for the generated files.
 * The new target source set's name is the input source set name's* suffixed with
 * "GeneratedRest", e.g. "mainGeneratedRest". The plugin invokes
 * RestLiResourceModelExporter to generate idl (.restspec.json) files for each
 * IdlItem in the input source set's pegasus IdlOptions. The generated idl files
 * will be in target source set's idl directory, e.g. "src/mainGeneratedRest/idl".
 * For example, the following adds an IdlItem to the source set's pegasus IdlOptions.
 * This line should appear in the impl project's build.gradle. If no IdlItem is added,
 * this source set will be excluded from generating idl and checking idl compatibility,
 * even there are existing idl files.
 * <pre>
 *   pegasus.main.idlOptions.addIdlItem(["com.linkedin.restli.examples.groups.server"])
 * </pre>
 * </p>
 *
 * <p>
 * After the idl generation phase, each included idl file is checked for compatibility against
 * those in the api project. In case the current interface breaks compatibility,
 * by default the build fails and reports all compatibility errors and warnings. Otherwise,
 * the build tasks in the api project later will package the resource classes into jar files.
 * User can change the compatibility requirement between the current and published idl by
 * setting the "rest.model.compatibility" project property, i.e.
 * "gradle -Prest.model.compatibility=<strategy> ..." The following levels are supported:
 * <ul>
 *   <li><b>off</b>: idl compatibility check will not be performed at all. This is only suggested to be
 *   used in test environments.</li>
 *   <li><b>ignore</b>: idl compatibility check will occur but its result will be ignored.
 *   The result will be aggregated and printed at the end of the build.</li>
 *   <li><b>backwards</b>: build fails if there are backwards incompatible changes in idl.
 *   Build continues if there are only compatible changes.</li>
 *   <li><b>equivalent (default)</b>: build fails if there is any functional changes (compatible or
 *   incompatible) in the current idl. Only docs and comments are allowed to be different.</li>
 * </ul>
 * The plugin needs to know where the api project is. It searches the api project in the
 * following steps. If all searches fail, the build fails.
 * <ol>
 *   <li>
 *     Use the specified project from the impl project build.gradle file. The <i>ext.apiProject</i>
 *     property explicitly assigns the api project. E.g.
 *     <pre>
 *       ext.apiProject = project(':groups:groups-server-api')
 *     </pre>
 *     If multiple such statements exist, the last will be used. Wrong project path causes Gradle
 *     evaluation error.
 *   </li>
 *   <li>
 *     If no <i>ext.apiProject</i> property is defined, the plugin will try to guess the
 *     api project name with the following conventions. The search stops at the first successful match.
 *     <ol>
 *       <li>
 *         If the impl project name ends with the following suffixes, substitute the suffix with "-api".
 *           <ol>
 *             <li>-impl</li>
 *             <li>-service</li>
 *             <li>-server</li>
 *             <li>-server-impl</li>
 *           </ol>
 *         This list can be overridden by inserting the following line to the project build.gradle:
 *         <pre>
 *           ext.apiProjectSubstitutionSuffixes = ['-new-suffix-1', '-new-suffix-2']
 *         </pre>
 *         Alternatively, this setting could be applied globally to all projects by putting it in
 *         the <i>subprojects</i> section of the root build.gradle.</b>
 *       </li>
 *       <li>
 *         Append "-api" to the impl project name.
 *       </li>
 *     </ol>
 *   </li>
 * </ol>
 * The plugin invokes RestLiResourceModelCompatibilityChecker to check compatibility.
 * </p>
 *
 * <p>
 * The idl files in the api project are not generated by the plugin, but rather
 * "published" from the impl project. The publishRestModel task is used to copy the
 * idl files to the api project. This task is invoked automatically if the idls are
 * verified to be "safe". "Safe" is determined by the "rest.model.compatibility"
 * property. Because this task is skipped if the idls are functionally equivalent
 * (not necessarily identical, e.g. differ in doc fields), if the default "equivalent"
 * compatibility level is used, no file will be copied. If such automatic publishing
 * is intended to be skip, set the "rest.model.skipPublish" property to true.
 * Note that all the properties are per-project and can be overridden in each project's
 * build.gradle file.
 * </p>
 *
 * <p>
 * Please always keep in mind that if idl publishing is happened, a subsequent whole-project
 * rebuild is necessary to pick up the changes. Otherwise, the Hudson job will fail and
 * the source code commit will fail.
 * </p>
 *
 * <p>
 * The configurations that apply to generating the rest model and rest client jars
 * are as follow:
 * <ul>
 *   <li>
 *     The restClientCompile configuration specifies the classpath for compiling
 *     the generated rest client source (.java) files. In most cases,
 *     it should be the Pegasus restli-client jar.
 *     (The default compile configuration is not used for compiling rest client because
 *     it is not desirable to include non rest client dependencies, such as
 *     the rest server implementation classes, in the data template jar.)
 *     The configuration should not directly include data template jars. Data template jars
 *     should be included in the dataModel configuration.
 *   </li>
 *   <li>
 *     The dataModel configuration provides the value of the "generator.resolver.path"
 *     system property that is passed to RestRequestBuilderGenerator.
 *     This configuration should contain only data template jars. The data template jars
 *     contain both data schema (.pdsc) files and generated data template (.class) files.
 *     The RestRequestBuilderGenerator will only generate rest client classes.
 *     The dataModel configuration is also included in the compile classpath for the
 *     generated rest client source files. The dataModel configuration does not
 *     include generated data template classes, then the Java compiler may not able to
 *     find the data template classes referenced by the generated rest client.
 *   </li>
 *   <li>
 *     The testDataModel configuration is similar to the dataModel configuration
 *     except it is used when generating rest client source files from
 *     test source sets.
 *   </li>
 *   <li>
 *     The restModel configuration is used to publish the rest model jar
 *     which contains generated idl (.restspec.json) files.
 *   </li>
 *   <li>
 *     The testRestModel configuration is similar to the restModel configuration
 *     except it is used to publish rest model jar files generated from
 *     test source sets.
 *   </li>
 *   <li>
 *     The restClient configuration is used to publish the rest client jar
 *     which contains both generated idl (.restspec.json) files and
 *     the rest client class (.class) files generated from from these
 *     idl (.restspec.json) files.
 *   </li>
 *   <li>
 *     The testRestClient configuration is similar to the restClient configuration
 *     except it is used to publish rest client jar files generated from
 *     test source sets.
 *   </li>
 * </ul>
 * </p>
 *
 * <p>
 * This plugin considers test source sets whose names begin with 'test' or 'integTest' to be
 * test source sets.
 * </p>
 */

class PegasusPlugin implements Plugin<Project>
{
  public static boolean debug = false

  //
  // Constants for generating sourceSet names and corresponding directory names
  // for generated code
  //
  private static final String DATA_TEMPLATE_GEN_TYPE = 'DataTemplate'
  private static final String REST_GEN_TYPE = 'Rest'
  private static final String AVRO_SCHEMA_GEN_TYPE = 'AvroSchema'

  private static final String DATA_TEMPLATE_FILE_SUFFIX = '.pdsc'
  private static final String IDL_FILE_SUFFIX = '.restspec.json'
  private static final String SNAPSHOT_FILE_SUFFIX = '.snapshot.json'
  private static final String TEST_DIR_REGEX = '^(integ)?[Tt]est'

  private static final String SNAPSHOT_COMPAT_REQUIREMENT = 'rest.model.compatibility'
  private static final String SNAPSHOT_NO_PUBLISH = 'rest.model.noPublish'
  private static final String IDL_COMPAT_REQUIREMENT = 'rest.idl.compatibility'
  private static final String IDL_NO_PUBLISH = 'rest.idl.noPublish'

  private static final String GENERATOR_CLASSLOADER_NAME = 'pegasusGeneratorClassLoader'

  private static boolean _runOnce = false

  private static final StringBuffer _restModelCompatMessage = new StringBuffer()
  private static final StringBuffer _restModelPublishReminder = new StringBuffer()

  private static final Object STATIC_BUILD_FINISHED_LOCK = new Object()

  private Class<? extends Plugin> _thisPluginType = getClass().asSubclass(Plugin)
  private Task _generateSourcesJarTask = null
  private Task _generateJavadocTask = null
  private Task _generateJavadocJarTask = null

  void setPluginType(Class<? extends Plugin> pluginType)
  {
    _thisPluginType = pluginType
  }

  void setSourcesJarTask(Task sourcesJarTask)
  {
    _generateSourcesJarTask = sourcesJarTask
  }

  void setJavadocJarTask(Task javadocJarTask)
  {
    _generateJavadocJarTask = javadocJarTask
  }

  @Override
  void apply(Project project) {
    project.plugins.apply(JavaPlugin)
    project.plugins.apply(IdeaPlugin)
    project.plugins.apply(EclipsePlugin)

    // this HashMap will have a PegasusOptions per sourceSet
    project.ext.set('pegasus', new HashMap<String, PegasusOptions>())
    // this map will extract PegasusOptions.GenerationMode to project property
    project.ext.set('PegasusGenerationMode', PegasusOptions.GenerationMode.values().collectEntries {[it.name(), it]})

    if (!_runOnce) {
      synchronized (STATIC_BUILD_FINISHED_LOCK) {
        if (!_runOnce) { //double-check pattern for efficiency
          project.gradle.buildFinished { BuildResult result ->
            final StringBuilder endOfBuildMessage = new StringBuilder()

            if (_restModelCompatMessage.length() > 0) {
              endOfBuildMessage.append(_restModelCompatMessage)
            }

            if (_restModelPublishReminder.length() > 0) {
              endOfBuildMessage.append(_restModelPublishReminder)
            }

            if (endOfBuildMessage.length() > 0) {
              result.gradle.rootProject.logger.quiet(endOfBuildMessage.toString())
            }
          }
          _runOnce = true
        }
      }
    }

    project.configurations {
      // configuration for compiling generated data templates
      dataTemplateCompile {
        visible = false
      }

      // configuration for running rest client generator
      restClientCompile {
        visible = false
      }

      // configuration for running data template generator
      // DEPRECATED! This configuration is no longer used. Please stop using it.
      dataTemplateGenerator {
        visible = false
      }

      // configuration for running rest client generator
      // DEPRECATED! This configuration is no longer used. Please stop using it.
      restTools {
        visible = false
      }

      // configuration for running Avro schema generator
      // DEPRECATED! To skip avro schema generation, use PegasusOptions.generationModes
      avroSchemaGenerator {
        visible = false
      }

      // configuration for depending on data schemas and potentially generated data templates
      // and for publishing jars containing data schemas to the project artifacts for including in the ivy.xml
      dataModel
      testDataModel {
        extendsFrom dataModel
      }

      // configuration for depending on data schemas and potentially generated data templates
      // and for publishing jars containing data schemas to the project artifacts for including in the ivy.xml
      avroSchema
      testAvroSchema {
        extendsFrom avroSchema
      }

      // configuration for publishing jars containing data schemas and generated data templates
      // to the project artifacts for including in the ivy.xml
      //
      // published data template jars depends on the configurations used to compile the classes
      // in the jar, this includes the data models/templates used by the data template generator
      // and the classes used to compile the generated classes.
      dataTemplate {
        extendsFrom dataTemplateCompile
        extendsFrom dataModel
      }
      testDataTemplate {
        extendsFrom dataTemplate
        extendsFrom testDataModel
      }

      // configuration for publishing jars containing rest idl and generated client builders
      // to the project artifacts for including in the ivy.xml
      //
      // published client builder jars depends on the configurations used to compile the classes
      // in the jar, this includes the data models/templates (potentially generated by this
      // project and) used by the data template generator and the classes used to compile
      // the generated classes.
      restClient {
        extendsFrom restClientCompile
        extendsFrom dataTemplate
      }
      testRestClient {
        extendsFrom restClient
        extendsFrom testDataTemplate
      }
    }

    // this call has to be here because:
    // 1) artifact cannot be published once projects has been evaluated, so we need to first
    // create the tasks and artifact handler, then progressively append sources
    // 2) in order to append sources progressively, the source and documentation tasks and artifacts must be
    // configured/created before configuring and creating the code generation tasks.

    configureGeneratedSourcesAndJavadoc(project)

    project.sourceSets.all { SourceSet sourceSet ->

      if (sourceSet.name =~ '[Gg]enerated') {
        return
      }

      checkAvroSchemaExist(project, sourceSet)

      // the idl Generator input options will be inside the PegasusOptions class. Users of the
      // plugin can set the inputOptions in their build.gradle
      project.pegasus[sourceSet.name] = new PegasusOptions()

      // rest model generation could fail on incompatibility
      // if it can fail, fail it early
      configureRestModelGeneration(project, sourceSet)

      configureDataTemplateGeneration(project, sourceSet)

      configureAvroSchemaGeneration(project, sourceSet)

      configureRestClientGeneration(project, sourceSet)

      Task cleanGeneratedDirTask = project.task(sourceSet.getTaskName('clean', 'GeneratedDir')) << {
        project.delete(getGeneratedSourceDirName(project, sourceSet, DATA_TEMPLATE_GEN_TYPE))
        project.delete(getGeneratedSourceDirName(project, sourceSet, REST_GEN_TYPE))
        project.delete(getGeneratedSourceDirName(project, sourceSet, AVRO_SCHEMA_GEN_TYPE))
      }
      // make clean depends on deleting the generated directories
      project.tasks.clean.dependsOn(cleanGeneratedDirTask)
    }

    project.ext.set(GENERATOR_CLASSLOADER_NAME, this.class.classLoader)
  }

  protected void configureGeneratedSourcesAndJavadoc(Project project)
  {
    _generateJavadocTask = project.task('generateJavadoc', type: Javadoc)

    if (_generateSourcesJarTask == null)
    {
      //
      // configuration for publishing jars containing sources for generated classes
      // to the project artifacts for including in the ivy.xml
      //
      def generatedSourcesConfig = project.configurations.add('generatedSources')
      def testGeneratedSourcesConfig = project.configurations.add('testGeneratedSources') {
        extendsFrom(generatedSourcesConfig)
      }

      _generateSourcesJarTask = project.task('generateSourcesJar', type: Jar) {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        description = 'Generates a jar file containing the sources for the generated Java classes.'

        classifier = 'sources'
      }

      project.artifacts {
        generatedSources _generateSourcesJarTask
      }
    }

    if (_generateJavadocJarTask == null)
    {
      //
      // configuration for publishing jars containing Javadoc for generated classes
      // to the project artifacts for including in the ivy.xml
      //
      def generatedJavadocConfig = project.configurations.add('generatedJavadoc')
      def testGeneratedJavadocConfig = project.configurations.add('testGeneratedJavadoc') {
        extendsFrom(generatedJavadocConfig)
      }

      _generateJavadocJarTask = project.task('generateJavadocJar', type: Jar, dependsOn: _generateJavadocTask) {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        description = 'Generates a jar file containing the Javadoc for the generated Java classes.'

        classifier = 'javadoc'
        from _generateJavadocTask.destinationDir
      }

      project.artifacts {
        generatedJavadoc _generateJavadocJarTask
      }
    }
    else
    {
      _generateJavadocJarTask.from(_generateJavadocTask.destinationDir)
      _generateJavadocJarTask.dependsOn(_generateJavadocTask)
    }
  }

  private static addGeneratedDir(Project project, SourceSet sourceSet, Collection<Configuration> configurations)
  {
    // stupid if block needed because of stupid assignment required to update source dirs
    if (isTestSourceSet(sourceSet))
    {
      Set<File> sourceDirs = project.ideaModule.module.testSourceDirs
      sourceDirs.addAll(sourceSet.java.srcDirs)
      // this is stupid but assignment is required
      project.ideaModule.module.testSourceDirs = sourceDirs
      if (debug) System.out.println("Added ${sourceSet.java.srcDirs} to project.ideaModule.module.testSourceDirs ${project.ideaModule.module.testSourceDirs}")
    }
    else
    {
      Set<File> sourceDirs = project.ideaModule.module.sourceDirs
      sourceDirs.addAll(sourceSet.java.srcDirs)
      // this is stupid but assignment is required
      project.ideaModule.module.sourceDirs = sourceDirs
      if (debug) System.out.println("Added ${sourceSet.java.srcDirs} to project.ideaModule.module.sourceDirs ${project.ideaModule.module.sourceDirs}")
    }
    Collection compilePlus = project.ideaModule.module.scopes.COMPILE.plus
    compilePlus.addAll(configurations)
    project.ideaModule.module.scopes.COMPILE.plus = compilePlus
  }

  private static void checkAvroSchemaExist(Project project, SourceSet sourceSet)
  {
    final String sourceDir = "src${File.separatorChar}${sourceSet.name}"
    final File avroSourceDir = project.file("${sourceDir}${File.separatorChar}avro")
    if (avroSourceDir.exists())
    {
      project.logger.lifecycle("${project.name}'s ${sourceDir} has non-empty avro directory. pegasus plugin does not process avro directory")
    }
  }

  private static String getDataSchemaRelativePath(Project project, SourceSet sourceSet)
  {
    if (project.hasProperty('overridePegasusDir') && project.overridePegasusDir) {
      return project.overridePegasusDir
    }
    return "src${File.separatorChar}${sourceSet.name}${File.separatorChar}pegasus"
  }

  private static String getSnapshotRelativePath(Project project, SourceSet sourceSet)
  {
    if (project.hasProperty('overrideSnapshotDir') && project.overrideSnapshotDir) {
      return project.overrideSnapshotDir
    }
    return "src${File.separatorChar}${sourceSet.name}${File.separatorChar}snapshot"
  }

  private static String getIdlRelativePath(Project project, SourceSet sourceSet)
  {
    if (project.hasProperty('overrideIdlDir') && project.overrideIdlDir) {
      return project.overrideIdlDir
    }
    return "src${File.separatorChar}${sourceSet.name}${File.separatorChar}idl"
  }

  private static FileTree getSuffixedFiles(Project project, Object baseDir, String suffix)
  {
    return project.fileTree(dir: baseDir, includes: ["**${File.separatorChar}*${suffix}".toString()]);
  }

  private static boolean isTestSourceSet(SourceSet sourceSet)
  {
    return (boolean)(sourceSet.name =~ TEST_DIR_REGEX)
  }

  private static Configuration getDataModelConfig(Project project, SourceSet sourceSet)
  {
    return (isTestSourceSet(sourceSet) ? project.configurations.testDataModel : project.configurations.dataModel)
  }

  protected void configureRestModelGeneration(Project project, SourceSet sourceSet)
  {

    if (sourceSet.allSource.empty)
    {
      project.logger.info("No source files found for sourceSet " + sourceSet.name + ".  Skipping idl generation.")
      return
    }

    // afterEvaluate needed so that api project can be overridden via ext.apiProject
    project.afterEvaluate {
      // find api project here instead of in each project's plugin configuration
      // this allows api project relation options (ext.api*) to be specified anywhere in the build.gradle file
      // alternatively, pass closures to task configuration, and evaluate the closures when task is executed
      final Project apiProject = getApiProject(project)

      // make sure the api project is evaluated. Important for configure-on-demand mode.
      if (apiProject)
      {
        project.evaluationDependsOn(apiProject.path)
      }

      if (apiProject && !apiProject.plugins.hasPlugin(_thisPluginType))
      {
        apiProject = null
      }

      if (apiProject == null)
      {
        return
      }

      final Task jarTask = project.tasks.findByName(sourceSet.getJarTaskName())
      if (jarTask == null || !(jarTask instanceof Jar))
      {
        return
      }

      // generate the rest model
      final String destinationDirPrefix = getGeneratedSourceDirName(project, sourceSet, REST_GEN_TYPE) + File.separatorChar
      final FileCollection restModelResolverPath = apiProject.files(getDataSchemaRelativePath(project, sourceSet)) + getDataModelConfig(apiProject, sourceSet)

      final Task generateRestModelTask = project.task(sourceSet.getTaskName('generate', 'restModel'),
                                                      type: GenerateRestModel,
                                                      dependsOn: project.tasks[sourceSet.compileJavaTaskName]) {
        inputDirs = sourceSet.java.srcDirs
        // we need all the artifacts from runtime for any private implementation classes the server code might need.
        runtimeClasspath = project.configurations.runtime + sourceSet.runtimeClasspath
        snapshotDestinationDir = project.file(destinationDirPrefix + 'snapshot')
        idlDestinationDir = project.file(destinationDirPrefix + 'idl')
        idlOptions = project.pegasus[sourceSet.name].idlOptions
        resolverPath = restModelResolverPath
        generatedSnapshotFiles = getSuffixedFiles(project, snapshotDestinationDir, SNAPSHOT_FILE_SUFFIX).files
        generatedIdlFiles = getSuffixedFiles(project, idlDestinationDir, IDL_FILE_SUFFIX).files
      }

      final File apiSnapshotDir = apiProject.file(getSnapshotRelativePath(apiProject, sourceSet))
      final File apiIdlDir = apiProject.file(getIdlRelativePath(apiProject, sourceSet))
      apiSnapshotDir.mkdirs()
      apiIdlDir.mkdirs()

      final Task checkRestModelTask = project.task(sourceSet.getTaskName('check', 'RestModel'),
                                                   type: CheckRestModel,
                                                   dependsOn: generateRestModelTask) {
        currentSnapShotFiles = generateRestModelTask.generatedSnapshotFiles
        currentIdlFiles = generateRestModelTask.generatedIdlFiles
        previousSnapshotDirectory = apiSnapshotDir
        previousIdlDirectory = apiIdlDir
        resolverPath = restModelResolverPath
      }

      final Task checkIdlTask = project.task(sourceSet.getTaskName('check', 'Idl'),
                                             type: CheckIdl,
                                             dependsOn: generateRestModelTask) {
        currentIdlFiles = generateRestModelTask.generatedIdlFiles
        previousIdlDirectory = apiIdlDir
        resolverPath = restModelResolverPath
      }

      // rest model publishing involves cross-project reference
      // configure after all projects have been evaluated
      // the file copy can be turned off by "rest.model.noPublish" flag
      final Task publishRestliSnapshotTask = project.task(sourceSet.getTaskName('publish', 'RestliSnapshot'),
                                                          type: PublishRestModel,
                                                          dependsOn: [checkRestModelTask, checkIdlTask]) {
        from generateRestModelTask.generatedSnapshotFiles
        into apiSnapshotDir
        suffix = SNAPSHOT_FILE_SUFFIX

        onlyIf {
          !isPropertyTrue(project, SNAPSHOT_NO_PUBLISH) &&
                  checkRestModelTask.state.executed &&
                  checkRestModelTask.state.failure == null &&
                  !checkRestModelTask.isEquivalent
        }
      }
      final Task publishRestliIdlTask = project.task(sourceSet.getTaskName('publish', 'RestliIdl'),
                                                     type: PublishRestModel,
                                                     dependsOn: publishRestliSnapshotTask) {
        from generateRestModelTask.generatedIdlFiles
        into apiIdlDir
        suffix = IDL_FILE_SUFFIX

        onlyIf {
          !isPropertyTrue(project, IDL_NO_PUBLISH) &&
                  checkRestModelTask.state.executed &&
                  checkRestModelTask.state.failure == null &&
                  !checkRestModelTask.isEquivalent &&
                  checkIdlTask.state.executed &&
                  checkIdlTask.state.failure == null &&
                  !checkIdlTask.isEquivalent
        }
      }
      project.logger.info("API project selected for $publishRestliIdlTask.path is $apiProject.path")

      // tasks which declare no output should always assume outputs UP-TO-DATE
      publishRestliIdlTask.outputs.upToDateWhen { true }
      publishRestliSnapshotTask.outputs.upToDateWhen { true }

      jarTask.from(generateRestModelTask.generatedIdlFiles) // add generated .restspec.json files as resources to the jar
      jarTask.dependsOn(publishRestliIdlTask)
    }
  }

  protected void configureAvroSchemaGeneration(Project project, SourceSet sourceSet)
  {
    final File dataSchemaDir = project.file(getDataSchemaRelativePath(project, sourceSet))
    final FileTree dataSchemaFiles = getSuffixedFiles(project, dataSchemaDir, DATA_TEMPLATE_FILE_SUFFIX)

    if (dataSchemaFiles.empty)
    {
      project.logger.info("Skipping configuration of avro schema generation in $project because there are no data schema files.")
      return
    }

    // generate avro schema files from data schema
    File avroDir = project.file(getGeneratedSourceDirName(project, sourceSet, AVRO_SCHEMA_GEN_TYPE) + File.separatorChar + 'avro')
    final Task generateAvroSchemaTask = project.task(sourceSet.getTaskName('generate', 'avroSchema'), type: GenerateAvroSchema) {
      inputDir = dataSchemaDir
      destinationDir = avroDir
      inputDataSchemaFiles = dataSchemaFiles
      resolverPath = getDataModelConfig(project, sourceSet)

      onlyIf {
        project.pegasus[sourceSet.name].hasGenerationMode(PegasusOptions.GenerationMode.AVRO) ||
        !project.configurations.avroSchemaGenerator.empty
      }
    }

    project.tasks[sourceSet.compileJavaTaskName].dependsOn(generateAvroSchemaTask)

    // create avro schema jar file

    Task avroSchemaJarTask = project.task(sourceSet.name + 'AvroSchemaJar', type: Jar) {
      // add path prefix to each file in the data schema directory
      from (avroDir) {
        eachFile {
          it.path = 'avro' + File.separatorChar + it.path.toString()
        }
      }
      appendix = getAppendix(sourceSet, 'avro-schema')
      description = 'Generate an avro schema jar'
    }

    if (!isTestSourceSet(sourceSet))
    {
      project.artifacts {
        avroSchema avroSchemaJarTask
      }
    }
    else
    {
      project.artifacts {
        testAvroSchema avroSchemaJarTask
      }
    }
  }

  protected void configureDataTemplateGeneration(Project project, SourceSet sourceSet)
  {
    final File dataSchemaDir = project.file(getDataSchemaRelativePath(project, sourceSet))
    final FileTree dataSchemaFiles = getSuffixedFiles(project, dataSchemaDir, DATA_TEMPLATE_FILE_SUFFIX)
    if (dataSchemaFiles.empty)
    {
      return
    }

    // generate data template source files from data schema
    final Task generateDataTemplatesTask = project.task(sourceSet.getTaskName('generate', 'dataTemplate'), type: GenerateDataTemplate) {
      inputDir = dataSchemaDir
      destinationDir = project.file(getGeneratedSourceDirName(project, sourceSet, DATA_TEMPLATE_GEN_TYPE) + File.separatorChar + 'java')
      inputDataSchemaFiles = dataSchemaFiles
      resolverPath = getDataModelConfig(project, sourceSet)

      onlyIf {
        project.pegasus[sourceSet.name].hasGenerationMode(PegasusOptions.GenerationMode.PEGASUS)
      }
    }

    _generateSourcesJarTask.from(generateDataTemplatesTask.destinationDir)
    _generateSourcesJarTask.dependsOn(generateDataTemplatesTask)

    _generateJavadocTask.source(generateDataTemplatesTask.destinationDir)
    _generateJavadocTask.classpath += project.configurations.dataTemplateCompile + generateDataTemplatesTask.resolverPath
    _generateJavadocTask.dependsOn(generateDataTemplatesTask)

    // create new source set for generated java source and class files
    String targetSourceSetName = getGeneratedSourceSetName(sourceSet, DATA_TEMPLATE_GEN_TYPE)
    SourceSet targetSourceSet = project.sourceSets.create(targetSourceSetName) {
      java {
        srcDir "src${File.separatorChar}${targetSourceSetName}${File.separatorChar}java"
      }
      compileClasspath = getDataModelConfig(project, sourceSet) + project.configurations.dataTemplateCompile
    }

    // idea plugin needs to know about new generated java source directory and its dependencies
    addGeneratedDir(project, targetSourceSet, [ getDataModelConfig(project, sourceSet), project.configurations.dataTemplateCompile ])

    // make sure that java source files have been generated before compiling them
    final Task compileTask = project.tasks[targetSourceSet.compileJavaTaskName]
    compileTask.dependsOn(generateDataTemplatesTask)

    // create data template jar file
    Task dataTemplateJarTask = project.task(sourceSet.name + 'DataTemplateJar',
                                            type: Jar,
                                            dependsOn: compileTask) {
      from (dataSchemaDir) {
        eachFile {
          it.path = 'pegasus' + File.separatorChar + it.path.toString()
        }
      }
      from (targetSourceSet.output)
      appendix = getAppendix(sourceSet, 'data-template')
      description = 'Generate a data template jar'
    }

    // add the data model and date template jars to the list of project artifacts.
    if (!isTestSourceSet(sourceSet))
    {
      project.artifacts {
        dataTemplate dataTemplateJarTask
      }
    }
    else
    {
      project.artifacts {
        testDataTemplate dataTemplateJarTask
      }
    }

    // include additional dependencies into the appropriate configuration used to compile the input source set
    // must include the generated data template classes and their dependencies the configuration
    String compileConfigName = (isTestSourceSet(sourceSet)) ? 'testCompile' : 'compile'
    project.configurations {
      "${compileConfigName}" {
        extendsFrom(getDataModelConfig(project, sourceSet))
        extendsFrom(project.configurations.dataTemplateCompile)
      }
    }
    project.dependencies.add(compileConfigName, new DefaultSelfResolvingDependency(project.files(dataTemplateJarTask.archivePath)))

    if (debug)
    {
      System.out.println('configureDataTemplateGeneration sourceSet ' + sourceSet.name)
      System.out.println("${compileConfigName}.allDependenices : " + project.configurations[compileConfigName].allDependencies)
      System.out.println("${compileConfigName}.extendsFrom: " + project.configurations[compileConfigName].extendsFrom)
      System.out.println("${compileConfigName}.transitive: " + project.configurations[compileConfigName].transitive)
    }

    project.tasks[sourceSet.compileJavaTaskName].dependsOn(dataTemplateJarTask)
  }

  // Generate rest client from idl files generated from java source files in the specified source set.
  //
  // This generates rest client source files from idl file generated from java source files
  // in the source set. The generated rest client source files will be in a new source set.
  // It also compiles the rest client source files into classes, and creates both the
  // rest model and rest client jar files.
  //
  protected void configureRestClientGeneration(Project project, SourceSet sourceSet)
  {
    // idl directory for api project
    final File idlDir = project.file(getIdlRelativePath(project, sourceSet))
    if (getSuffixedFiles(project, idlDir, IDL_FILE_SUFFIX).empty)
    {
      return
    }

    // always include imported data template jars in compileClasspath of rest client
    FileCollection dataModels = getDataModelConfig(project, sourceSet)

    // if data templates generated from this source set, add the generated data template jar to compileClasspath
    // of rest client.
    String dataTemplateSourceSetName = getGeneratedSourceSetName(sourceSet, DATA_TEMPLATE_GEN_TYPE)
    Task dataTemplateJarTask = null
    if (project.sourceSets.findByName(dataTemplateSourceSetName) != null)
    {
      if (debug) System.out.println("sourceSet ${sourceSet.name} has generated sourceSet ${dataTemplateSourceSetName}")
      dataTemplateJarTask = project.tasks[sourceSet.name + 'DataTemplateJar']
      dataModels += project.files(dataTemplateJarTask.archivePath)
    }

    // create source set for generated rest model, rest client source and class files.
    String targetSourceSetName = getGeneratedSourceSetName(sourceSet, REST_GEN_TYPE)
    SourceSet targetSourceSet = project.sourceSets.create(targetSourceSetName) {
      java {
        srcDir "src${File.separatorChar}${targetSourceSetName}${File.separatorChar}java"
      }
      compileClasspath = dataModels + project.configurations.restClientCompile
    }
    project.plugins.withType(EclipsePlugin) {
      project.eclipse.classpath.plusConfigurations += project.configurations.restClientCompile
    }

    // idea plugin needs to know about new rest client source directory and its dependencies
    addGeneratedDir(project, targetSourceSet, [ getDataModelConfig(project, sourceSet), project.configurations.restClientCompile ])

    // generate the rest client source files
    Task generateRestClientTask = project.task(targetSourceSet.getTaskName('generate', 'restClient'), type: GenerateRestClient) {
      inputDir = idlDir
      resolverPath = dataModels
      destinationDir = project.file(getGeneratedSourceDirName(project, sourceSet, REST_GEN_TYPE) + File.separatorChar + 'java')
    }

    if (dataTemplateJarTask != null)
    {
      generateRestClientTask.dependsOn(dataTemplateJarTask)
    }

    _generateSourcesJarTask.from(generateRestClientTask.destinationDir)
    _generateSourcesJarTask.dependsOn(generateRestClientTask)

    _generateJavadocTask.source(generateRestClientTask.destinationDir)
    _generateJavadocTask.classpath += project.configurations.restClientCompile + generateRestClientTask.resolverPath
    _generateJavadocTask.dependsOn(generateRestClientTask)

    // make sure rest client source files have been generated before compiling them
    Task compileGeneratedRestClientTask = project.tasks[targetSourceSet.compileJavaTaskName]
    compileGeneratedRestClientTask.dependsOn(generateRestClientTask)

    // create the rest client jar file
    def restClientJarTask = project.task(sourceSet.name + 'RestClientJar',
                                         type: Jar,
                                         dependsOn: compileGeneratedRestClientTask) {
      from (idlDir) {
        eachFile {
          project.logger.lifecycle('Add interface file: ' + it.toString() )
          it.path = 'idl' + File.separatorChar + it.path.toString()
        }
        includes = ['*' + IDL_FILE_SUFFIX]
      }
      from (targetSourceSet.output)
      appendix = getAppendix(sourceSet, 'rest-client')
      description = 'Generate rest client jar'
    }

    // add the rest model jar and the rest client jar to the list of project artifacts.
    if (!isTestSourceSet(sourceSet))
    {
      project.artifacts {
        restClient restClientJarTask
      }
    }
    else
    {
      project.artifacts {
        testRestClient restClientJarTask
      }
    }
  }

  // Compute the name of the source set that will contain a type of an input generated code.
  // e.g. genType may be 'DataTemplate' or 'Rest'
  private static String getGeneratedSourceSetName(SourceSet sourceSet, String genType)
  {
    return "${sourceSet.name}Generated${genType}"
  }

  // Compute the directory name that will contain a type generated code of an input source set.
  // e.g. genType may be 'DataTemplate' or 'Rest'
  private static String getGeneratedSourceDirName(Project project, SourceSet sourceSet, String genType)
  {
    final String sourceSetName = getGeneratedSourceSetName(sourceSet, genType)
    return "${project.projectDir}${File.separatorChar}src${File.separatorChar}${sourceSetName}"
  }

  // Return the appendix for generated jar files.
  // The source set name is not included for the main source set.
  private static String getAppendix(SourceSet sourceSet, String suffix)
  {
    return (sourceSet.name.equals('main') ? suffix : "${sourceSet.name}-${suffix}")
  }

  private static Project getApiProject(Project project)
  {
    if (project.ext.has('apiProject'))
    {
      return project.ext.apiProject
    }

    List subsSuffixes = [ '-impl', '-service', '-server', '-server-impl' ]
    if (project.ext.has('apiProjectSubstitutionSuffixes'))
    {
      subsSuffixes = project.ext.apiProjectSubstitutionSuffixes
    }

    for (String suffix: subsSuffixes)
    {
      if (project.path.endsWith(suffix))
      {
        final String searchPath = project.path.substring(0, project.path.length() - suffix.length()) + '-api'
        final Project apiProject = project.findProject(searchPath)
        if (apiProject != null)
        {
          return apiProject
        }
      }
    }

    return project.findProject(project.path + '-api')
  }

  /**
   * Return true if the given property exists and its value is true
   *
   * @param project the project where to look for the property
   * @param property the name of the property
   */
  public static boolean isPropertyTrue(Project project, String property)
  {
    return project.hasProperty(property) && Boolean.valueOf(project.property(property).toString())
  }

  /**
   * Generate the data template source files from data schema files.
   *
   * To use this plugin, add these three lines to your build.gradle:
   * <pre>
   * apply plugin: 'li-pegasus2'
   * </pre>
   *
   * The plugin will scan the source set's pegasus directory, e.g. "src/main/pegasus"
   * for data schema (.pdsc) files.
   */
  static class GenerateDataTemplate extends DefaultTask
  {
    /**
     * Directory to write the generated data template source files.
     */
    @OutputDirectory File destinationDir
    /**
     * Directory containing the data schema files.
     */
    @InputDirectory File inputDir
    /**
     * The input arguments to PegasusDataTemplateGenerator class (excludes the destination directory).
     */
    @InputFiles FileTree inputDataSchemaFiles
    /**
     * The resolver path.
     */
    @InputFiles FileCollection resolverPath

    @TaskAction
    protected void generate()
    {
      project.logger.info('Generating data templates ...')
      destinationDir.mkdirs()
      project.logger.lifecycle("There are ${inputDataSchemaFiles.files.size()} data schema input files. Using input root folder: ${inputDir}")

      final String resolverPathStr = (resolverPath + project.files(inputDir)).collect { it.path }.join(File.pathSeparator)
      final Class<?> dataTemplateGenerator = project.property(GENERATOR_CLASSLOADER_NAME).loadClass('com.linkedin.pegasus.generator.PegasusDataTemplateGenerator')
      dataTemplateGenerator.run(resolverPathStr, null, null, destinationDir.path, inputDataSchemaFiles.collect { it.path } as String[])
    }
  }

  /**
   * Generate the Avro schema (.avsc) files from data schema files.
   *
   * To use this plugin, add these three lines to your build.gradle:
   * <pre>
   * apply plugin: 'li-pegasus2'
   * </pre>
   *
   * The plugin will scan the source set's pegasus directory, e.g. "src/main/pegasus"
   * for data schema (.pdsc) files.
   */
  static class GenerateAvroSchema extends DefaultTask
  {
    /**
     * Directory to write the generated Avro schema files.
     */
    @OutputDirectory File destinationDir
    /**
     * Directory containing the data schema files.
     */
    @InputDirectory File inputDir
    /**
     * The input arguments to AvroSchemaGenerator class (excludes the destination directory).
     */
    @InputFiles FileTree inputDataSchemaFiles
    /**
     * The resolver path.
     */
    @InputFiles FileCollection resolverPath

    @TaskAction
    protected void generate()
    {
      project.logger.info('Generating Avro schemas ...')
      destinationDir.mkdirs()
      project.logger.lifecycle("There are ${inputDataSchemaFiles.files.size()} data schema input files. Using input root folder: ${inputDir}")

      final String resolverPathStr = (resolverPath + project.files(inputDir)).collect { it.path }.join(File.pathSeparator)
      final Class<?> avroSchemaGenerator = project.property(GENERATOR_CLASSLOADER_NAME).loadClass('com.linkedin.data.avro.generator.AvroSchemaGenerator')
      avroSchemaGenerator.run(resolverPathStr, null, destinationDir.path, inputDataSchemaFiles.collect { it.path } as String[])
    }
  }

  /**
   * Generate the idl file from the annotated java classes. This also requires access to the
   * classes that were used to compile these java classes.
   * Projects with no IdlItem will be excluded from this task
   *
   * As prerequisite of this task, add these lines to your build.gradle:
   * <pre>
   * apply plugin: 'li-pegasus2'
   * </pre>
   *
   * Optionally, to generate idl for specific packages, add
   * <pre>
   * pegasus.&lt;sourceSet&gt;.idlOptions.addIdlItem(['&lt;packageName&gt;'])
   * </pre>
   */
  static class GenerateRestModel extends DefaultTask
  {
    @InputFiles Set<File> inputDirs
    @InputFiles FileCollection runtimeClasspath
    @OutputDirectory File snapshotDestinationDir
    @OutputDirectory File idlDestinationDir
    @InputFiles FileCollection resolverPath
    PegasusOptions.IdlOptions idlOptions
    Collection<File> generatedIdlFiles
    Collection<File> generatedSnapshotFiles

    @TaskAction
    protected void generate()
    {
      final String[] inputDirPaths = inputDirs.collect { it.path }
      project.logger.debug("GenerateRestModel using input directories ${inputDirPaths}")
      project.logger.debug("GenerateRestModel using destination dir ${idlDestinationDir.path}")
      snapshotDestinationDir.mkdirs()
      idlDestinationDir.mkdirs()

      // handle multiple idl generations in the same project, see pegasus rest-framework-server-examples
      // for example.
      // by default, scan in all source files for annotated java classes.
      // specifically, to scan in certain packages, use
      //   pegasus.<sourceSet>.idlOptions.addIdlItem(['<packageName>'])
      // where [<packageName>] is the array of packages that should be searched for annotated java classes.
      // for example:
      // pegasus.main.idlOptions.addIdlItem(['com.linkedin.groups.server.rest.impl', 'com.linkedin.greetings.server.rest.impl'])
      // they will still be placed in the same jar, though

      final ClassLoader generatorClassLoader = (ClassLoader) project.property(GENERATOR_CLASSLOADER_NAME)
      final ClassLoader prevContextClassLoader = Thread.currentThread().contextClassLoader
      final URL[] classpathUrls = runtimeClasspath.collect { it.toURI().toURL() } as URL[]
      Thread.currentThread().contextClassLoader = new URLClassLoader(classpathUrls, generatorClassLoader)

      final snapshotGenerator = generatorClassLoader.loadClass('com.linkedin.restli.tools.snapshot.gen.RestLiSnapshotExporter').newInstance()
      final idlGenerator = generatorClassLoader.loadClass('com.linkedin.restli.tools.idlgen.RestLiResourceModelExporter').newInstance()

      final String resolverPathStr = resolverPath.collect { it.path }.join(File.pathSeparator)
      snapshotGenerator.setResolverPath(resolverPathStr)

      if (idlOptions.idlItems.empty)
      {
        final snapshotResult = snapshotGenerator.export(null, classpathUrls as String[], inputDirPaths, null, null, snapshotDestinationDir.path)
        final idlResult = idlGenerator.export(null, classpathUrls as String[], inputDirPaths, null, null, idlDestinationDir.path)

        generatedSnapshotFiles.addAll(snapshotResult.targetFiles)
        generatedIdlFiles.addAll(idlResult.targetFiles)
      }
      else
      {
        for (PegasusOptions.IdlItem idlItem: idlOptions.idlItems)
        {
          final String apiName = idlItem.apiName
          if (apiName.length() == 0)
          {
            project.logger.info('Generating interface for unnamed api ...')
          }
          else
          {
            project.logger.info("Generating interface for api: ${apiName} ...")
          }

          // RestLiResourceModelExporter will load classes from the passed packages
          // we need to add the classpath to the thread's context class loader
          final snapshotResult = snapshotGenerator.export(apiName, classpathUrls as String[], inputDirPaths, idlItem.packageNames, null, snapshotDestinationDir.path)
          final idlResult = idlGenerator.export(apiName, classpathUrls as String[], inputDirPaths, idlItem.packageNames, null, idlDestinationDir.path)

          generatedSnapshotFiles.addAll(snapshotResult.targetFiles)
          generatedIdlFiles.addAll(idlResult.targetFiles)
        }
      }

      Thread.currentThread().contextClassLoader = prevContextClassLoader
    }
  }

  static class CheckRestModel extends DefaultTask
  {
    @InputFiles Collection<File> currentSnapShotFiles
    @InputFiles Collection<File> currentIdlFiles
    @InputDirectory File previousSnapshotDirectory
    @InputDirectory File previousIdlDirectory
    @InputFiles FileCollection resolverPath
    boolean isEquivalent = false
    private static _snapshotFilter = new FileExtensionFilter(SNAPSHOT_FILE_SUFFIX)
    private static _idlFilter = new FileExtensionFilter(IDL_FILE_SUFFIX)

    @TaskAction
    protected void check()
    {
      final ClassLoader generatorClassLoader = (ClassLoader) project.property(GENERATOR_CLASSLOADER_NAME)
      final Class<? extends Enum> compatLevelClass = generatorClassLoader.loadClass('com.linkedin.restli.tools.idlcheck.CompatibilityLevel').asSubclass(Enum.class)

      final Enum idlCompatLevel
      if (project.hasProperty(SNAPSHOT_COMPAT_REQUIREMENT))
      {
        try
        {
          idlCompatLevel = Enum.valueOf(compatLevelClass, project.property(SNAPSHOT_COMPAT_REQUIREMENT).toString().toUpperCase())
        }
        catch (IllegalArgumentException e)
        {
          throw new Exception("Unrecognized compatibility level property.", e)
        }
      }
      else
      {
        idlCompatLevel = compatLevelClass.getEnumConstants().last()
      }

      if (idlCompatLevel.ordinal() == 0)
      {
        project.logger.info('Interface compatibility checking is turned off.')
        return
      }

      project.logger.info('Checking interface compatibility with API ...')

      final Class<?> snapshotCheckerClass = generatorClassLoader.loadClass('com.linkedin.restli.tools.snapshot.check.RestLiSnapshotCompatibilityChecker')
      final snapshotCompatibilityChecker = snapshotCheckerClass.newInstance()
      final String resolverPathStr = resolverPath.collect { it.path }.join(File.pathSeparator)
      snapshotCompatibilityChecker.setResolverPath(resolverPathStr)

      final StringBuilder allCheckMessage = new StringBuilder()
      boolean isCompatible = true
      final errorFilePairs = []

      final Set<String> apiExistingSnapshotFilePaths = previousSnapshotDirectory.listFiles(_snapshotFilter).collect { it.absolutePath }
      currentSnapShotFiles.each {
        project.logger.info('Checking interface file: ' + it.path)

        String apiSnapshotFilePath = "${previousSnapshotDirectory.path}${File.separatorChar}${it.name}"
        final File apiSnapshotFile = project.file(apiSnapshotFilePath)
        if (apiSnapshotFile.exists())
        {
          apiExistingSnapshotFilePaths.remove(apiSnapshotFilePath)

          final infoMap = snapshotCompatibilityChecker.check(apiSnapshotFilePath, it.path, idlCompatLevel)
          final boolean isCurrentSnapshotCompatible = infoMap.isCompatible(idlCompatLevel)
          isCompatible &= isCurrentSnapshotCompatible

          project.logger.info("Checked compatibility in mode: $idlCompatLevel; $apiSnapshotFilePath VS $it.path; result: $isCurrentSnapshotCompatible")
          allCheckMessage.append(infoMap.createSummary(apiSnapshotFilePath, it.path))
        }
        else
        {
          errorFilePairs.add(["", it.path])
        }
      }

      final Set<String> apiExistingIdlFilePaths = previousIdlDirectory.listFiles(_idlFilter).collect { it.absolutePath }
      currentIdlFiles.each {
        String apiIdlFilePath = "${previousIdlDirectory.path}${File.separatorChar}${it.name}"
        final File apiIdlFile = project.file(apiIdlFilePath)
        if (apiIdlFile.exists())
        {
          apiExistingIdlFilePaths.remove(apiIdlFilePath)
        }
        else
        {
          errorFilePairs.add(["", it.path])
        }
      }

      (apiExistingSnapshotFilePaths + apiExistingIdlFilePaths).each {
        errorFilePairs.add([it, ""])
      }

      final restModelFileChecker = snapshotCheckerClass.newInstance()
      errorFilePairs.each {
        final infoMap = restModelFileChecker.check(it[0], it[1], idlCompatLevel)
        isCompatible &= infoMap.isCompatible(idlCompatLevel)
        allCheckMessage.append(infoMap.createSummary())
      }

      if (allCheckMessage.length() == 0)
      {
        assert(isCompatible)
        isEquivalent = true
        return
      }

      allCheckMessage.append("\n")
                     .append("You may add \"-P${SNAPSHOT_COMPAT_REQUIREMENT}=backwards\" to the build command to allow backwards compatible changes in interface.\n")
                     .append("You may use \"-P${SNAPSHOT_COMPAT_REQUIREMENT}=ignore\" to ignore compatibility errors.\n")
                     .append("Documentation: https://github.com/linkedin/rest.li/wiki/Gradle-build-integration#compatibility")

      if (isCompatible)
      {
        _restModelCompatMessage.append(allCheckMessage)
      }
      else
      {
        throw new Exception(allCheckMessage.toString())
      }
    }

    private static class FileExtensionFilter implements FileFilter
    {
      FileExtensionFilter(String suffix)
      {
        _suffix = suffix
      }

      public boolean accept(File pathname)
      {
        return pathname.isFile() && pathname.name.toLowerCase().endsWith(_suffix);
      }

      private String _suffix
    }
  }

  static class CheckIdl extends DefaultTask
  {
    @InputFiles Collection<File> currentIdlFiles
    @InputDirectory File previousIdlDirectory
    @InputFiles FileCollection resolverPath
    boolean isEquivalent = false
    private static _idlFilter = new CheckRestModel.FileExtensionFilter(IDL_FILE_SUFFIX)

    @TaskAction
    protected void check()
    {
      final ClassLoader generatorClassLoader = (ClassLoader) project.property(GENERATOR_CLASSLOADER_NAME)
      final Class<? extends Enum> compatLevelClass = generatorClassLoader.loadClass('com.linkedin.restli.tools.idlcheck.CompatibilityLevel').asSubclass(Enum.class)

      final Enum idlCompatLevel
      if (project.hasProperty(IDL_COMPAT_REQUIREMENT))
      {
        try
        {
          idlCompatLevel = Enum.valueOf(compatLevelClass, project.property(IDL_COMPAT_REQUIREMENT).toString().toUpperCase())
        }
        catch (IllegalArgumentException e)
        {
          throw new Exception("Unrecognized compatibility level property.", e)
        }
      }
      else
      {
        idlCompatLevel = compatLevelClass.getEnumConstants().first()
      }

      if (idlCompatLevel.ordinal() == 0)
      {
        project.logger.info('Interface compatibility checking is turned off.')
        return
      }

      project.logger.info('Checking interface compatibility with API ...')

      final Class<?> idlCheckerClass = generatorClassLoader.loadClass('com.linkedin.restli.tools.idlcheck.RestLiResourceModelCompatibilityChecker')
      final idlCompatibilityChecker = idlCheckerClass.newInstance()
      final String resolverPathStr = resolverPath.collect { it.path }.join(File.pathSeparator)
      idlCompatibilityChecker.setResolverPath(resolverPathStr)

      final StringBuilder allCheckMessage = new StringBuilder()
      boolean isCompatible = true
      final errorFilePairs = []

      final Set<String> apiExistingIdlFilePaths = previousIdlDirectory.listFiles(_idlFilter).collect { it.absolutePath }
      currentIdlFiles.each {
        project.logger.info('Checking interface file: ' + it.path)

        String apiSnapshotFilePath = "${previousIdlDirectory.path}${File.separatorChar}${it.name}"
        final File apiSnapshotFile = project.file(apiSnapshotFilePath)
        if (apiSnapshotFile.exists())
        {
          apiExistingIdlFilePaths.remove(apiSnapshotFilePath)

          final boolean isCurrentSnapshotCompatible = idlCompatibilityChecker.check(apiSnapshotFilePath, it.path, idlCompatLevel)
          isCompatible &= isCurrentSnapshotCompatible

          project.logger.info("Checked compatibility in mode: $idlCompatLevel; $apiSnapshotFilePath VS $it.path; result: $isCurrentSnapshotCompatible")
          allCheckMessage.append(idlCompatibilityChecker.infoMap.createSummary(apiSnapshotFilePath, it.path))
        }
        else
        {
          errorFilePairs.add(["", it.path])
        }
      }

      (apiExistingIdlFilePaths + apiExistingIdlFilePaths).each {
        errorFilePairs.add([it, ""])
      }

      final restModelFileChecker = idlCheckerClass.newInstance()
      errorFilePairs.each {
        isCompatible &= restModelFileChecker.check(it[0], it[1], idlCompatLevel)
      }
      allCheckMessage.append(restModelFileChecker.infoMap.createSummary())

      if (allCheckMessage.length() == 0)
      {
        assert(isCompatible)
        isEquivalent = true
        return
      }

      allCheckMessage.append("\n")
              .append("You may add \"-P${IDL_COMPAT_REQUIREMENT}=backwards\" to the build command to allow backwards compatible changes in interface.\n")
              .append("You may use \"-P${IDL_COMPAT_REQUIREMENT}=ignore\" to ignore compatibility errors.\n")
              .append("Documentation: https://github.com/linkedin/rest.li/wiki/Gradle-build-integration#compatibility")

      if (isCompatible)
      {
        _restModelCompatMessage.append(allCheckMessage)
      }
      else
      {
        throw new Exception(allCheckMessage.toString())
      }
    }
  }

  /**
   * Check idl compatibility between current project and the api project.
   * If check succeeds and not equivalent, copy all idl files to the api project.
   * This task overwrites existing api idl files.
   *
   * As prerequisite of this task, the api project needs to be designated. There are multiple ways to do this.
   * Please refer to the documentation section for detail.
   */
  static class PublishRestModel extends Copy
  {
    String suffix

    @Override
    protected void copy()
    {
      if (source.empty)
      {
        project.logger.error('No interface file is found. Skip publishing interface.')
        return
      }

      project.logger.lifecycle('Publishing rest model to API project ...')

      final FileTree apiRestModelFiles = getSuffixedFiles(project, destinationDir, suffix)
      final int apiRestModelFileCount = apiRestModelFiles.files.size()

      super.copy()

      // FileTree is lazily evaluated, so that it scans for files only when the contents of the file tree are queried
      if (apiRestModelFileCount != 0 && apiRestModelFileCount != apiRestModelFiles.files.size())
      {
        project.logger.warn(suffix + ' files count changed after publish. You may have duplicate files with different names.')
      }

      if (_restModelPublishReminder.length() == 0)
      {
        _restModelPublishReminder.append("\n")
                                 .append("Rest model files have been changed during the build. You must run the following command line at project root to pick up the changes:\n")
                                 .append("  gradle build")
      }
    }
  }

  /**
   * This task will generate the rest client source files.
   *
   * As pre-requisite of this task,, add these lines to your build.gradle:
   * <pre>
   * apply plugin: 'li-pegasus2'
   * </pre>
   *
   * Optionally, you can specify certain resource classes to be generated idl
   * <pre>
   * pegasus.<sourceSet>.clientOptions.addClientItem('<restModelFilePath>', '<defaultPackage>', <keepDataTemplates>)
   * </pre>
   * keepDataTemplates is a boolean that isn't used right now, but might be implemented in the future.
   */
  static class GenerateRestClient extends DefaultTask
  {
    @InputDirectory File inputDir
    @InputFiles FileCollection resolverPath
    @OutputDirectory File destinationDir

    @TaskAction
    protected void generate()
    {
      PegasusOptions.ClientOptions pegasusClientOptions = new PegasusOptions.ClientOptions()

      // idl input could include rest model jar files
      project.files(inputDir).each { input ->
        if (input.isDirectory())
        {
          for (File f: getSuffixedFiles(project, input, IDL_FILE_SUFFIX))
          {
            if (!pegasusClientOptions.hasRestModelFileName(f.name))
            {
              pegasusClientOptions.addClientItem(f.name, '', false)
              project.logger.lifecycle("Add interface file: ${f.path}")
            }
          }
        }
      }
      if (pegasusClientOptions.clientItems.empty)
      {
        return
      }

      project.logger.info('Generating REST client builders ...')

      final String resolverPathStr = resolverPath.collect { it.path }.join(File.pathSeparator)
      final Class<?> stubGenerator = project.property(GENERATOR_CLASSLOADER_NAME).loadClass('com.linkedin.restli.tools.clientgen.RestRequestBuilderGenerator')
      destinationDir.mkdirs()

      for (PegasusOptions.ClientItem clientItem: pegasusClientOptions.clientItems)
      {
        project.logger.lifecycle("Generating rest client source files for: ${clientItem.restModelFileName}")
        project.logger.lifecycle("Destination directory: ${destinationDir}")

        final String defaultPackage
        if (clientItem.defaultPackage.equals("") && project.hasProperty('idlDefaultPackage') && project.idlDefaultPackage)
        {
          defaultPackage = project.idlDefaultPackage
        }
        else
        {
          defaultPackage = clientItem.defaultPackage
        }

        final String restModelFilePath = "${inputDir}${File.separatorChar}${clientItem.restModelFileName}"
        stubGenerator.run(resolverPathStr, defaultPackage, false, false, destinationDir.path, [restModelFilePath] as String[])
      }
    }
  }
}
