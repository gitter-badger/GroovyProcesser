package org.bartoleo.groovyprocesser

//@Grab(group='org.apache.commons', module='commons-lang3', version='3.1')

import groovy.json.JsonSlurper
import org.apache.commons.lang3.text.WordUtils
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.codehaus.groovy.tools.LoaderConfiguration
import org.codehaus.groovy.tools.RootLoader

import java.beans.Introspector

class Processer {

    final Binding binding
    final GroovyShell shell
    def script
    String lastGroovyScript = ""
    Map<String, Object> cache = [:]

    Processer(String pPathBase) {

        URLClassLoader loader = GroovyObject.class.classLoader
        if (pPathBase) {
            File fileLib = new File(pPathBase + File.separator + "lib")
            if (fileLib.exists()){
                def p = ~/.*\.jar/
                fileLib.eachFileMatch(p) {
                    loader.addURL(it.toURI().toURL())
                }
            }
        }

//       if (pPathBase){
//            File fileLib = new File(pPathBase+File.separator+"lib")
//            def p = ~/.*\.jar/
//            fileLib.eachFileMatch(p) {
//                println it.toURI().toURL()
//                groovyClassLoader.addURL(it.toURI().toURL())
//            }
////            groovyClassLoader.addClasspath(pPathBase+File.separator+"lib")
////            println pPathBase+File.separator+"lib"
//        }
        // add POJO base class, and classpath
        CompilerConfiguration cc = new CompilerConfiguration();
//        cc.setScriptBaseClass( this.class.getName() );
        //cc.setClasspathList(classpath);
//        if (pPathBase){
//            cc.setClasspathList([pPathBase+File.separator+"lib"+File.separator+"sqljdbc4.jar"])
//            println cc.classpath
//        }

// inject default imports
        ImportCustomizer ic = new ImportCustomizer();
//        ic.addImports( ServiceUtils.class.getName() );
        cc.addCompilationCustomizers(ic);

        LoaderConfiguration loaderConfiguration = new LoaderConfiguration()
        if (pPathBase) {
            loaderConfiguration.addClassPath(pPathBase + File.separator + "lib" + File.separator + "*")
            cc.classpath = pPathBase + File.separator + "lib" + File.separator + "*"
        }
        RootLoader rootLoader = new RootLoader(loaderConfiguration)

// inject context and properties
        binding = new Binding();

// parse the recipe text file
        this.shell = new GroovyShell(rootLoader, binding, cc)
    }

    public def evaluateInput(String pText) {
        def result
        if (!pText) {
            return ""
        }
        result = pText
        if (pText.startsWith("@gp:")) {
            String firstLine = getFirstLine(pText)
            if (firstLine.length() > 4) {
                String stringActions = firstLine.substring(4, firstLine.length());
                def actions = stringActions.split(",")
                result = stripFirstLine(pText)
                actions.each { action ->
                    if (action == "groovy") {
                        result = shell.evaluate(result)
                    }
                    if (action == "json") {
                        def slurper = new JsonSlurper()
                        result = slurper.parseText(result)
                    }
                    if (action == "xml") {
                        def slurper = new XmlSlurper()
                        result = slurper.parseText(result)
                    }
                    if (action == "url") {
                        result = getUrlTextWithCache(result);
                    }
                    if (action == "file") {
                        result = new File(result).getText();
                    }
                    if (action == "cmd") {
                        def command = result
                        def proc = command.execute()
                        def outStream = new ByteArrayOutputStream(4096)
                        def errStream = new ByteArrayOutputStream(4096)
                        proc.waitForProcessOutput(outStream, errStream)
                        result = outStream.toString()
                    }

                }
            }
        }
        return result
    }

    String getUrlTextWithCache(String pUrl) {
        //simple caching system as a concept
        //TODO: use something better
        if (!cache.containsKey(pUrl)) {
            File fileTmpCache = File.createTempFile("GP_URL", ".cache")
            fileTmpCache.deleteOnExit()
            //FIXME: better timeout???
            fileTmpCache << pUrl.toURL().getText([connectTimeout: 500, readTimeout: 30000])
            cache[pUrl] = fileTmpCache
        } else {
            println "cache hit"
        }
        return cache[pUrl].text
    }

    public String getFirstLine(pText) {
        int index = pText.indexOf("\n")
        if (index >= 0) {
            return pText.substring(0, index)
        }
        return pText
    }

    public String stripFirstLine(pText) {
        int index = pText.indexOf("\n")
        if (index >= 0) {
            return pText.substring(index + 1)
        }
        return pText
    }

    def process(String pInput, def pGroovyScript, ProcesserOutputInterface pProcesserOutput) {
        String result

//        System.out.println("inizio")

        //redirect output to stream, so I'll read output written with print
        def saveOut = System.out
        def buf = new ByteArrayOutputStream()
        def newOut = new PrintStream(buf)
        System.out = newOut


        try {

            binding.setVariable("processer", this)
            binding.setVariable("input", evaluateInput(pInput))
            binding.setVariable("setInput", { valore -> binding.setVariable("input", valore); pProcesserOutput.setInput(valore); })
            if (pGroovyScript != lastGroovyScript) {
                script = shell.parse(pGroovyScript)
                lastGroovyScript = pGroovyScript
            }
            def returned = script.run()

            if (returned) {
                result = returned.toString()
            } else {
                result = ""
            }

            result += buf.toString()

            System.out = saveOut
            pProcesserOutput.setOutput(result)
        } catch (Throwable ex) {
            System.out = saveOut
            pProcesserOutput.setOutputOnException(ex)
        } finally {
            System.out = saveOut
        }

//        System.out.println("fine")

        return result

    }


    public String toPropertyName(String pLine) {
        String propertyName

        if (!pLine) {
            return ""
        }

        propertyName = pLine.replace("_", " ")
        propertyName = propertyName.replace("-", " ")
        propertyName = WordUtils.capitalize(propertyName)
        propertyName = propertyName.replace(" ", "")

        return Introspector.decapitalize(propertyName)

    }

    public String toGetter(String pLine) {
        String propertyName

        if (!pLine) {
            return ""
        }

        propertyName = toPropertyName(pLine)

        return "get" + WordUtils.capitalize(propertyName) + "(" + ")"

    }

    public String toSetter(String pLine, String pValue) {
        String propertyName

        if (!pLine) {
            return ""
        }

        propertyName = toPropertyName(pLine)

        return "set" + WordUtils.capitalize(propertyName) + "(" + pValue + ")"

    }

    public String toCamelCase( String text) {
        //alias for toPropertyName
        return toPropertyName(text)
    }

    public String toSnakeCase( String text ) {
        text.replaceAll( /([A-Z])/, /_$1/ ).toLowerCase().replaceAll( /^_/, '' )
    }

    public String toSausageCase( String text ) {
        text.replaceAll( /([A-Z])/, /-$1/ ).toLowerCase().replaceAll( /^-/, '' )
    }


}
