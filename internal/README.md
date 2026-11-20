# internal

Local tools around the Axon Ivy Rules Engine, kept out of the product: nothing in the repository refers to this
folder. The repository builds, tests and ships without it.

## The editor page check

`editor-smoke` starts a throwaway container of `axonivy/rule-engine-runtime:dev` with an API key and uses the
page `/editor/` of the decision runtime like a person would: the API key prompt, open a model, change a rule in the
KIE editor, save and activate, activate the old version, roll back, a model the runtime rejects, a new model, a
`.dmn` file written back. Every request that leaves the runtime is blocked and
counts as a failure. The container is removed afterwards.

```
docker compose build                 # in the repository root, after a change of the runtime
cd internal/editor-smoke
npm install                          # once
npm run smoke                        # 33 checks, screenshots in .preview/
npm run smoke -- --url=http://localhost:8090/ --key=<key>   # against a runtime that runs already; adds versions to it
```

Needs Docker, Node.js 22 or newer and Chrome or Edge (set `CHROME_PATH` if neither is found).
