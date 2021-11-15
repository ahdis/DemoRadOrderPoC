# DemoRadOrderPoC

See http://build.fhir.org/ig/ahdis/ch-rad-poc/usecase-english.html#get-new-radiology-order-02

- Polls https://test.ahdis.ch/matchbox/fhir for new tasks which are requested by a filler
- If task can be handled task and related resources will be downloaded, task updated and a new task will be created

The sample creates the task on the same server, for the PoC it will be on a differend server.

```
mvn package
mvn exec:java
```