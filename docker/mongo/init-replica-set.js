// Idempotent single-node replica-set init for local dev and CI. Run by the compose healthcheck.
//
// The member host is localhost:27017 so that the host-side URI used by run-local.sh,
//   mongodb://localhost:27017/kybr?replicaSet=rs0
// resolves the advertised member. Containers on the compose network connect with
//   mongodb://mongo:27017/kybr?directConnection=true
// (a direct connection to the primary still supports transactions).
try {
  rs.status();
} catch (e) {
  if (e.codeName === 'NotYetInitialized' || e.code === 94) {
    rs.initiate({ _id: 'rs0', members: [{ _id: 0, host: 'localhost:27017' }] });
  } else {
    throw e;
  }
}
// Healthy only once this node accepts writes.
quit(db.hello().isWritablePrimary ? 0 : 1);
