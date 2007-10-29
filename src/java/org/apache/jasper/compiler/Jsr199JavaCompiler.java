/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the "License").  You may not use this file except
 * in compliance with the License.
 *
 * You can obtain a copy of the license at
 * glassfish/bootstrap/legal/CDDLv1.0.txt or
 * https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * HEADER in each file and include the License file at
 * glassfish/bootstrap/legal/CDDLv1.0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your
 * own identifying information: Portions Copyright [yyyy]
 * [name of copyright owner]
 *
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

package org.apache.jasper.compiler;

import javax.tools.DiagnosticCollector;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.ToolProvider;
import javax.tools.JavaCompilerTool;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Reader;
import java.io.Writer;
import java.io.CharArrayWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Set;

import org.apache.jasper.JasperException;
import org.apache.jasper.Constants;


/**
 * Invoke Java Compiler per JSR 199, using in-memory storage for both the
 * input Java source and the generated bytecodes.
 *
 * @author Kin-man Chung
 */
public class Jsr199JavaCompiler implements JavaCompiler {

    private List<File> cpath;
    private JspRuntimeContext rtctxt;
    private BytecodeFile classFile;  // generated bytecodes
    private ArrayList<String> options = new ArrayList<String>();
    private CharArrayWriter charArrayWriter;

    public void init(JspCompilationContext ctxt,
                     ErrDispatcher err,
                     boolean suppressLogging) {
        rtctxt = ctxt.getRuntimeContext();
    }

    public void setClassPath(List<File> path) {
        this.cpath = path;
    }

    public void setExtdirs(String exts) {
        options.add("-extdirs");
        options.add(exts);
    }

    public void setSourceVM(String sourceVM) {
        options.add("-source");
        options.add(sourceVM);
    }

    public void setTargetVM(String targetVM) {
        options.add("-target");
        options.add(targetVM);
    }

    public void saveClassFile(String className, String classFileName) {
        rctxt.saveBytecode(className, classFileName);
    }

    public void removeJavaFile() {
        // no Java file generated
    }

    public void setDebug(boolean debug) {
        if (debug) {
            options.add("-g");
        }
    }

    public Writer getJavaWriter(String javaFileName, String javaEncoding) {
        this.charArrayWriter = new CharArrayWriter();
        return this.charArrayWriter;
    }

    public long getClassLastModified() {
        return rctxt.getBytecodeBirthTime(className);
    }

    public JavacErrorDetail[] compile(String className, Node.Nodes pageNodes)
            throws JasperException {

        final String source = charArrayWriter.toString();

        JavaCompilerTool javac = ToolProvider.getSystemJavaCompilerTool();

        DiagnosticCollector<JavaFileObject> diagnostics =
            new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager stdFileManager =
                                    javac.getStandardFileManager(diagnostics);

        String name = className.substring(className.lastIndexOf('.')+1);

        JavaFileObject[] sourceFiles = {
            new SimpleJavaFileObject(
                    URI.create("string:///" + name.replace('.','/') +
                               Kind.SOURCE.extension),
                    Kind.SOURCE) {
                public CharSequence getCharContent(boolean ignore) {
                    return source;
                }
            }
        };

        stdFileManager.setLocation(StandardLocation.CLASS_PATH, this.cpath);

        JavaCompilerTool.CompilationTask ct =
            javac.getTask(null,
                          getJavaFileManager(stdFileManager),
                          diagnostics,
                          options,
                          null, 
                          Arrays.asList(sourceFiles));

        ct.run();
        if (ct.getResult()) {
            rtctxt.setBytecode(className, classFile.getBytecode());
            return null;
        }

        // There are compilation errors!
        ArrayList<JavacErrorDetail> problems =
            new ArrayList<JavacErrorDetail>();
        for (Diagnostic dm: diagnostics.getDiagnostics()) {
            problems.add(ErrorDispatcher.createJavacError(
                source,
                pageNodes,
                new StringBuffer(dm.getMessage(null)),
                (int) dm.getLineNumber()));
        }
        return problems.toArray(new JavacErrorDetail[0]);
    }


    private class BytecodeFile extends SimpleJavaFileObject {

        private byte[] bytecode;
        private String className;

        BytecodeFile(URI uri, String className) {
            super(uri, Kind.CLASS);
            this.className = className;
        }

        String getClassName() {
            return this.className;
        }

        byte[] getBytecode() {
            return this.bytecode;
        }

        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                public void close() {
                    bytecode = this.toByteArray();
                }
            };
        }

        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytecode);
        }
    }


    private JavaFileObject getOutputFile(final String className,
                                         final URI uri) {

        classFile = new BytecodeFile(uri, className);

        // File the class file away, by its package name
        String packageName = className.substring(0, className.lastIndexOf("."));
        Map<String, ArrayList<JavaFileObject>> packageMap =
            rtctxt.getPackageMap();
        ArrayList<JavaFileObject> packageFiles = packageMap.get(packageName);
        if (packageFiles == null) {
            packageFiles = new ArrayList<JavaFileObject>();
            packageMap.put(packageName, packageFiles);
        }
        packageFiles.add(classFile);
        return classFile;
    }

    private JavaFileManager getJavaFileManager(JavaFileManager fm) {

        return new ForwardingJavaFileManager<JavaFileManager>(fm) {

/*
            @Override
            public FileObject getFileForOutput(Location location,
                                               String packageName,
                                               String relativeName,
                                               FileObject sibling){
                System.out.println(" At getFileForOutput: location = " +
                    location + " pachageName = " + packageName +
                    " relativeName = " + relativeName +
                    " sibling = " + sibling);
                return getOutputFile(relativeName, null);
            }
*/

            @Override
            public JavaFileObject getJavaFileForOutput(Location location,
                                                       String className,
                                                       Kind kind,
                                                       FileObject sibling){
                return getOutputFile(className,
                    URI.create("file:///" + className.replace('.','/') + kind));
            }

            @Override
            public String inferBinaryName(Location location,
                                          JavaFileObject file) {

                if (file instanceof BytecodeFile) {
                    return ((BytecodeFile)file).getClassName();
                }
                return super.inferBinaryName(location, file);
            }

            @Override
            public Iterable<JavaFileObject> list(Location location,
                                         String packageName,
                                         Set<Kind> kinds,
                                         boolean recurse)
                    throws IOException {

                if (location == StandardLocation.CLASS_PATH &&
                        packageName.startsWith(Constants.JSP_PACKAGE_NAME)) {

		    // TODO: Need to handle the case where some of the classes
                    // are on disk

                    Map<String, ArrayList<JavaFileObject>> packageMap =
                        rtctxt.getPackageMap();
                    ArrayList<JavaFileObject> packageFiles
                            = packageMap.get(packageName);
                    if (packageFiles != null) {
                        return packageFiles;
                    }
                }
                Iterable<JavaFileObject> lst =
                    super.list(location, packageName, kinds, recurse);

                return lst;
            }
        };

    }
}
