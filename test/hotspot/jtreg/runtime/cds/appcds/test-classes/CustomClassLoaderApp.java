import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Enumeration;
import java.io.IOException;

class CustomClassLoader {
    public CustomClassLoader(String jarPath)
        throws MalformedURLException, IOException {
        URL[] urls = { new URL("jar:file:" + jarPath + "!/") };
        jarFile = new JarFile(jarPath);
        urlClassLoader = URLClassLoader.newInstance(urls);
    }

    private URLClassLoader urlClassLoader;
    private JarFile jarFile;

    public Class loadClass(String name) throws ClassNotFoundException {
        Enumeration<JarEntry> e = jarFile.entries();
        while (e.hasMoreElements()) {
            JarEntry je = e.nextElement();
            if (je.isDirectory() || !je.getName().endsWith(".class")){
                continue;
            }
            String className = je.getName().substring(0, je.getName().length() - 6);
            if (!className.equals(name)) {
                continue;
            }
            className = className.replace('/', '.');
            return urlClassLoader.loadClass(className);
        }

        throw new ClassNotFoundException(name);
    }
}

public class CustomClassLoaderApp {
    public static void main(String[] args) throws Exception {
        CustomClassLoader classLoader = new CustomClassLoader(args[0]);
        Class<?> c = classLoader.loadClass("Outer$Inner");
        System.out.println("Declaring class: " + c.getDeclaringClass());
    }
}
