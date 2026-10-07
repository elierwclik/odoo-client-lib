# Origins, credits, and Odoo references

This Android/Kotlin adaptation is maintained by
[elierwclik](https://github.com/elierwclik) in
[elierwclik/odoo-client-lib](https://github.com/elierwclik/odoo-client-lib).
Its basis is Odoo's official Python client library,
[odoo/odoo-client-lib](https://github.com/odoo/odoo-client-lib),
version 2.0.1 of the material used for this adaptation.

## Original copyright holders

All original copyright notices are preserved:

- Stephane Wirtel.
- Nicolas Vanhoren, 2011.
- OpenERP s.a., 2011.
- Nicolas Seinlet, 2018.
- Odoo s.a., 2018.

Odoo s.a. and OpenERP s.a., together with the credited authors, retain their
copyrights in the original code. `elierwclik` identifies the adaptation's
maintainer and does not replace those original notices.

## Adaptation changes

Explicit Kotlin API, coroutines, Android HTTP transport, JSON/XML serialization,
CRUD helpers, JSON-2 introspection, UTC dates, tests, examples, documentation,
and AAR/Maven packaging. Original Python source files are not bundled in this
repository or its distributions.

## Official repositories and documentation

| Reference | Role |
| --- | --- |
| [odoo/odoo-client-lib](https://github.com/odoo/odoo-client-lib) | Official Python client library used as the basis of the Android adaptation. |
| [Original license](https://github.com/odoo/odoo-client-lib/blob/master/licence.txt) | Preserved copyright notices and BSD license. |
| [odoo/odoo](https://github.com/odoo/odoo) | Official server and method/protocol reference. |
| [Odoo JSON-2 API](https://www.odoo.com/documentation/19.0/developer/reference/external_api.html) | Endpoints and API-key authentication. |
| [Odoo RPC API](https://www.odoo.com/documentation/19.0/developer/reference/external_rpc_api.html) | RPC services and methods. |

The Odoo server is referenced for technical documentation. Its code is not
redistributed here, and the client library's BSD license is not attributed
to the server.

## License and redistribution

The original BSD 3-Clause license is preserved in [LICENSE](../LICENSE)
and the repository root's `LICENSE`. Credits and adaptation changes are described
in [NOTICE.txt](../NOTICE.txt).

The AAR and source JAR include `META-INF/ODOO-LICENSE.txt` and
`META-INF/ODOO-NOTICE.txt`. The example app retains them in its assets
and documentation. Preserve these texts when redistributing.

This is an independent adaptation. It does not imply endorsement by Odoo
or any original author, and their names are not presented as sponsors.
