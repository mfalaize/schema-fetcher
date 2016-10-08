package fr.mfalaize.utils;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.xpath.*;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * XSD Schemas URL fetcher. It is a improved code of the solution provided by mschwehl in the stackoverflow question :
 * http://stackoverflow.com/questions/13394950/download-xsd-with-all-imports.
 *
 * @author Maxime FALAIZE <maxime.falaize@gmail.com>
 */
public class SchemaFetcher {

    private String rootPath;

    // Key = schema URL and value the schema object with values inside
    private Map<String, Schema> cache = new HashMap<>();

    /**
     * Fetch all schemas from URL.
     *
     * @param args Required : First arg must be the filesystem absolute path to write the files to (without the separator at the end).
     *             Next you can add as many schemas urls as you want.
     * @throws XPathExpressionException
     * @throws IOException
     * @throws URISyntaxException
     */
    public static void main(String[] args) throws XPathExpressionException, IOException, URISyntaxException {
        if (args == null || args.length <= 1) {
            throw new IllegalArgumentException("Unexpected use of SchemaFetcher. You must provide root absolute path as first argument " +
                    "and schema URL next to it.");
        }
        String[] urls = new String[args.length - 1];
        System.arraycopy(args, 1, urls, 0, args.length - 1);
        new SchemaFetcher(args[0]).fetchAll(urls);
    }

    public SchemaFetcher(String rootPath) {
        this.rootPath = rootPath;
    }

    public void fetchAll(String... urls) throws IOException, XPathExpressionException, URISyntaxException {
        for (String url : urls) {
            Schema schema = new Schema(new URL(url));
            schema.fetchAll();
        }

        writeFiles();

        System.out.println("Done!");
    }

    private void writeFiles() throws IOException {
        for (String urlCache : cache.keySet()) {
            Schema element = cache.get(urlCache);
            File f = new File(rootPath + File.separator + element.fileName);

            System.out.println("Writing " + f.getAbsolutePath());

            String contentTemp = element.content;

            for (String schemaLocation : element.includesAndImports.keySet()) {
                Schema schema = element.includesAndImports.get(schemaLocation);
                if (schema.isHTTP()) {
                    contentTemp = contentTemp.replace(
                            "schemaLocation=\"" + schemaLocation,
                            "schemaLocation=\"" + schema.fileName);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(contentTemp.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    class Schema {

        private URL url;
        private String content;
        private String fileName;

        // Key = schemaLocation and value the include/import schema
        public Map<String, Schema> includesAndImports = new HashMap<>();

        public Schema(URL url) throws URISyntaxException {
            this.url = url;
            generateFileName();
        }

        public void fetchAll() throws IOException, XPathExpressionException, URISyntaxException {
            System.out.println("Fetching " + url.toString());

            try (Scanner scanner = new Scanner(url.openStream(), StandardCharsets.UTF_8.name())) {
                if (isHTTP()) {
                    content = scanner.useDelimiter("\\A").next();
                } else {
                    content = scanner.useDelimiter("\\Z").next();
                }

                InputSource source = new InputSource(new StringReader(content));

                List<Node> includesImportsList = getXpathNodes(source, "/*[local-name()='schema']/*[local-name()='include' or local-name()='import']");

                for (Node element : includesImportsList) {
                    Node sl = element.getAttributes().getNamedItem("schemaLocation");
                    if (sl == null) {
                        System.out.println(url + " defines one include/import but no schemaLocation");
                        continue;
                    }

                    String schemaLocation = sl.getNodeValue();

                    URL url = buildUrl(schemaLocation, this.url);

                    Schema schema = new Schema(url);
                    includesAndImports.put(schemaLocation, schema);
                }
            }

            cache.put(url.toString(), this);
            for (Schema includeOrImport : includesAndImports.values()) {
                if (!cache.containsKey(includeOrImport.url.toString())) {
                    includeOrImport.fetchAll();
                }
            }
        }

        private URL buildUrl(String schemaLocation, URL parent) throws MalformedURLException, URISyntaxException {

            if (schemaLocation.startsWith("http") || schemaLocation.startsWith("file")) {
                return new URL(schemaLocation);
            }

            // relative URL
            URI parentUri = parent.toURI().getPath().endsWith("/") ? parent.toURI().resolve("..") : parent.toURI().resolve(".");
            URL url = new URL(parentUri.toURL().toString() + schemaLocation);
            return new URL(url.toURI().normalize().toString());
        }

        /**
         * Try to use the schema filename in the URL. If it already exists in the cache then add the path before the filename.
         * Removes html:// and replaces / with _
         */
        public void generateFileName() throws URISyntaxException {
            URI parent = url.toURI().resolve(".");

            boolean uniqueFileName;
            do {
                uniqueFileName = true;
                fileName = parent.relativize(url.toURI()).toString();

                if (isHTTP()) {
                    fileName = fileName.replace("http://", "");
                    fileName = fileName.replace("/", "_");
                } else {
                    fileName = fileName.replace("/", "_");
                    fileName = fileName.replace("\\", "_");
                }

                for (Schema schema : cache.values()) {
                    if (schema.fileName != null && schema.fileName.equals(fileName)
                            && !schema.url.toString().equals(url.toString())) {
                        // Filename already exist for another schema. Generate from parent url
                        uniqueFileName = false;
                        parent = parent.resolve("..");
                    }
                }
            } while (!uniqueFileName);
        }

        public boolean isHTTP() {
            return url.getProtocol().startsWith("http");
        }

        private List<Node> getXpathNodes(InputSource source, String path) throws XPathExpressionException {
            List<Node> returnList = new ArrayList<>();

            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();
            XPathExpression expr = xpath.compile(path);

            NodeList nodeList = (NodeList) expr.evaluate(source, XPathConstants.NODESET);
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node n = nodeList.item(i);
                returnList.add(n);
            }

            return returnList;
        }
    }
}