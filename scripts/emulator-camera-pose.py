#!/usr/bin/env python3
"""Pose the emulator camera for a real QR scan, without disabling gRPC auth.

Development helper. Uses SDK-generated protobufs in workspace/emulator-proto,
grpcio, PyJWT and cryptography. Registers an ephemeral public key under the
emulator's documented JWK discovery directory and always removes that key.
Only physical pose RPCs are used; it does not deliver a decoded QR to the app.
"""
import argparse
import json
import sys
import time
from pathlib import Path
from uuid import uuid4

import grpc
import jwt
from cryptography.hazmat.primitives.asymmetric import ec


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--discovery", type=Path, required=True)
    parser.add_argument("--proto-dir", type=Path, default=Path("workspace/emulator-proto"))
    parser.add_argument("--position", nargs=3, type=float)
    parser.add_argument("--rotation", nargs=3, type=float)
    parser.add_argument("--restore", type=Path)
    parser.add_argument("--save", type=Path)
    args = parser.parse_args()
    config = dict(line.split("=", 1) for line in args.discovery.read_text().splitlines() if "=" in line)
    sys.path.insert(0, str(args.proto_dir.resolve()))
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as rpc
    key = ec.generate_private_key(ec.SECP256R1())
    public = json.loads(jwt.algorithms.ECAlgorithm.to_jwk(key.public_key()))
    public.update(kid="rivo-qr-" + uuid4().hex, use="sig", alg="ES256")
    key_file = Path(config["grpc.jwks"]) / (public["kid"] + ".jwk")
    key_file.write_text(json.dumps({"keys": [public]}))
    key_file.chmod(0o600)
    token = jwt.encode({"iss": "gradle-utp-emulator-control", "iat": int(time.time()),
                        "exp": int(time.time()) + 120}, key, algorithm="ES256", headers={"kid": public["kid"], "typ": None})
    metadata = (("authorization", "Bearer " + token),)
    try:
        with grpc.insecure_channel("127.0.0.1:" + config["grpc.port"]) as channel:
            stub = rpc.EmulatorControllerStub(channel)
            def get(target):
                return list(stub.getPhysicalModel(pb.PhysicalModelValue(target=target), metadata=metadata, timeout=3).value.data)
            for attempt in range(5):
                try:
                    state = {"position": get(pb.PhysicalModelValue.POSITION), "rotation": get(pb.PhysicalModelValue.ROTATION)}
                    break
                except grpc.RpcError:
                    if attempt == 4:
                        raise
                    time.sleep(0.2)
            if args.save:
                args.save.write_text(json.dumps(state))
            target = json.loads(args.restore.read_text()) if args.restore else {
                "position": args.position, "rotation": args.rotation}
            for name, enum in (("position", pb.PhysicalModelValue.POSITION), ("rotation", pb.PhysicalModelValue.ROTATION)):
                if target.get(name) is not None:
                    stub.setPhysicalModel(pb.PhysicalModelValue(target=enum,
                        value=pb.ParameterValue(data=target[name]), interpolation=pb.PhysicalModelValue.STEP),
                        metadata=metadata, timeout=3)
            print(json.dumps({"previousPose": state, "requestedPose": target}))
    finally:
        key_file.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
