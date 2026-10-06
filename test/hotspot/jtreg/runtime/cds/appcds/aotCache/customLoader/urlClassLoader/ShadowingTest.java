/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

/*
 * @test
 * @summary Test the effect of shadowing between URLClassLoader and its parent loader.
 * @requires vm.cds.supports.aot.class.linking
 * @library /test/lib /test/hotspot/jtreg/runtime/cds/appcds/aotCache/customLoader/test-classes
 * @build ShadowingTest URLClassLoaderFactory Shadower MyLoadeeA MyLoadeeB MyLoadeeC
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar app.jar ShadowingApp URLClassLoaderFactory
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar custParent.jar Shadower
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar custChild.jar MyLoadeeA MyLoadeeB
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar custC.jar MyLoadeeC
 * @run driver ShadowingTest AOT
 */

import java.io.InputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import jdk.test.lib.cds.CDSAppTester;
import jdk.test.lib.cds.CDSJarUtils;
import jdk.test.lib.helpers.ClassFileInstaller;
import jdk.test.lib.process.OutputAnalyzer;

public class ShadowingTest {
    private static final String mainClass = "ShadowingApp";

    public static void main(String... args) throws Exception {
        test(true);
        test(false);
    }

    static void test(boolean shadowInTraining) throws Exception {
        Tester tester = new Tester(shadowInTraining);
        tester.runAOTWorkflow();
    }

    static class Tester extends CDSAppTester {
        boolean shadowInTraining;
        public Tester(boolean shadowInTraining) {
            super(mainClass);
            this.shadowInTraining = shadowInTraining;
        }

        @Override
        public String classpath(RunMode runMode) {
            return "app.jar";
        }

        @Override
        public String[] vmArgs(RunMode runMode) {
            return new String[] {
                "-Xlog:aot+load,aot+class=debug",
                "-XX:+AOTCacheSupportForCustomLoader"
            };
        }

        @Override
        public String[] appCommandLine(RunMode runMode) {
            boolean doShadow = shadowInTraining || runMode == RunMode.PRODUCTION;
            return new String[] {
                mainClass,
                doShadow ? "shadow" : "noshadow",
            };
        }

        @Override
        public void checkExecution(OutputAnalyzer out, RunMode runMode) throws Exception {
            // FIXME ... add more checks
        }
    }
}

class ShadowingApp {
    public static void main(String args[]) throws Exception {
        boolean doShadow = args[0].equals("shadow");
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

        ClassLoader custParent = URLClassLoaderFactory.createURLClassLoader(systemClassLoader, "custParent.jar");
        System.out.println("Start .... " + (doShadow ? "shadowing" : "no shadowing"));
        if (doShadow) {
            // Define the parent class MyLoadeeA (from custChild.jar) in custParent
            Class<?> s = custParent.loadClass("Shadower");
            Consumer<byte[]> shadower = (Consumer<byte[]>)s.newInstance();
            shadower.accept(readJarEntry("custChild.jar", "MyLoadeeA.class"));
            Class<?> c1 = custParent.loadClass("MyLoadeeA");
            System.out.println("ShadowingApp: Defined " + c1 + " in " + c1.getClassLoader());

            // Define MyLoadeeC (from custC.jar) in the app class loader
            Class<?> c2 = MethodHandles.lookup().defineClass(readJarEntry("custC.jar", "MyLoadeeC.class"));
            System.out.println("ShadowingApp: Defined " + c2 + " in " + c2.getClassLoader());
        }

        ClassLoader custChild = URLClassLoaderFactory.createURLClassLoader(custParent, "custChild.jar");
        ClassLoader custC = URLClassLoaderFactory.createURLClassLoader(systemClassLoader, "custC.jar");

        custChild.loadClass("MyLoadeeB"); // MyLoadeeB is a child of MyLoadeeA
        custC.loadClass("MyLoadeeC");

        if (doShadow) {
            // MyLoadeeA is already defined in custParent, so custChild must not define its own version
            assertSameClass(custParent, custChild, "MyLoadeeA");

            // MyLoadeeC is already defined in systemClassLoader, so custC must not define its own version
            assertSameClass(systemClassLoader, custC, "MyLoadeeC");
        } else {
            mustNotLoad(custParent, "MyLoadeeA");
            mustNotLoad(systemClassLoader, "MyLoadeeC");
        }
    }

    static byte[] readJarEntry(String jarFile, String entryName) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new IOException("Entry not found: " + entryName);
            }

            try (var in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static void assertSameClass(ClassLoader a, ClassLoader b, String className) throws Exception {
        Class<?> clsA = a.loadClass(className);
        Class<?> clsB = b.loadClass(className);
        System.out.println("clsA.getClassLoader() = " + clsA.getClassLoader());
        System.out.println("clsB.getClassLoader() = " + clsB.getClassLoader());
        if (clsA != clsB) {
            throw new RuntimeException("Loaders " + a + " and " + b + " does not load the same class: " + className);
        }
    }

    static void mustNotLoad(ClassLoader loader, String className) throws Exception {
        try {
            loader.loadClass(className);
            throw new RuntimeException("Loader " + loader + " should not have loaded " + className);
        } catch (ClassNotFoundException e) {
            System.out.println("Loader " + loader + " cannot load " + className + " (as expected)");
        }
    }

}
