# Simple Http test server 1.0.0 (Java)

This is an Http/s server for automated testing of web client programs (eg. scrapers).
For ease of use the whole server is contained in one class and depends only on plain Java 8 runtime.
Tests require maven 3 or higher.


### Examples:
```java
    String url = SimpleHttpServer
      .getBuilder()
      .setContent("<html><body> My HTML </body></html>")
      .start();
```
First a static method getBuilder() creates a new server builder instance. Next, the setContent(String) stores the content to be served from the server in the builder. Lastly method start() constructs the instance of the server, starts it in a separate thread and returns a url under which the content is available.

Under the hood the start() method does the following:
* look up already running instance of the server and if not found
    * look for a free localhost port above 1665
    * start an http/s server at this port
* create a new handler and register it at the path /0 or next free integer. Url example: http://localhost:1666/2

### List of all Builder methods

setSsl() - switches the server to https mode.
setResponseCode(int) - sets the response code sent from the handler.
setHeaders(String) - sets the headers string sent from the handler.
setHeaders(Map<String, List<String>>) - sets the header map to be formatted and sent as headers from the handler.
start() - starts the server and returns the url of the handler.
startEcho() - starts echo handler which responds with the content of the request sent to the server.
setHandler(HttpHandler) - sets user defined handler.

### List of static SimpleHttpServer methods

getBuilder() - creates and returns a builder instance.
stop() - stops all server instances.

### Examples

Blocking handler example - pauses for a second before serving the word "Content"

```java
        byte[] content = "Content".getBytes();
        String url = SimpleHttpServer.getBuilder()
            .setHandler(httpExchange -> {
                try {
                    Thread.sleep(1000);
                    httpExchange.sendResponseHeaders(200, content.length);
                    OutputStream os = httpExchange.getResponseBody();
                    os.write(content);
                    os.close();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            })
            .start();
```

For further examples please refer to SimpleHttpServerTest class.
To run tests - cd to project's directory and run
```
mvn test
```
Have fun and let me know if you have some ideas for improvement.


Co-author: [Krzysztof Mochejski](https://github.com/krzysztofmo)
