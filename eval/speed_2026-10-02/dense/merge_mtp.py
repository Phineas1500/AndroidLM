#!/usr/bin/env python3
"""Append Qwen3.6's MTP block (blk.40, from bartowski's mtp-*.gguf) to our model file.

usage: merge_mtp.py ours.gguf mtp_header.bin blk40.bin blk40_start_offset out.gguf

ours.gguf      the model (733 tensors, block_count 40)
mtp_header.bin the start of bartowski's mtp gguf (header + tensor infos)
blk40.bin      the bytes of that file from the first blk.40 tensor to the end
blk40_start    that first blk.40 tensor's offset within the mtp file's data section
out.gguf       ours with block_count 41, nextn_predict_layers 1 and the blk.40 tensors appended
"""
import os, shutil, struct, sys

ALIGN = 32
SZ = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}


class Reader:
    def __init__(self, path):
        self.f = open(path, 'rb')

    def rd(self, fmt):
        return struct.unpack('<' + fmt, self.f.read(struct.calcsize('<' + fmt)))[0]

    def rbytes_str(self):
        n = self.rd('Q')
        return self.f.read(n)

    def skip_val(self, t):
        if t == 8:
            self.rbytes_str()
        elif t == 9:
            it = self.rd('I'); n = self.rd('Q')
            for _ in range(n):
                self.skip_val(it)
        else:
            self.f.read(SZ[t])


def read_header(path):
    r = Reader(path)
    assert r.f.read(4) == b'GGUF'
    ver = r.rd('I'); nt = r.rd('Q'); nkv = r.rd('Q')
    kvs = []  # (key, raw bytes of the whole entry)
    align = ALIGN
    for _ in range(nkv):
        start = r.f.tell()
        key = r.rbytes_str().decode()
        t = r.rd('I')
        if key == 'general.alignment':
            align = r.rd('I')
        else:
            r.skip_val(t)
        end = r.f.tell()
        r.f.seek(start); raw = r.f.read(end - start)
        kvs.append((key, t, raw))
    tensors = []
    for _ in range(nt):
        name = r.rbytes_str().decode(); nd = r.rd('I')
        dims = [r.rd('Q') for _ in range(nd)]; ty = r.rd('I'); off = r.rd('Q')
        tensors.append((name, dims, ty, off))
    data_start = (r.f.tell() + align - 1) // align * align
    return ver, kvs, tensors, data_start, align


def kv_u32(key, value):
    k = key.encode()
    return struct.pack('<Q', len(k)) + k + struct.pack('<I', 4) + struct.pack('<I', value)


def tensor_info(name, dims, ty, off):
    n = name.encode()
    out = struct.pack('<Q', len(n)) + n + struct.pack('<I', len(dims))
    out += b''.join(struct.pack('<Q', d) for d in dims)
    return out + struct.pack('<I', ty) + struct.pack('<Q', off)


def main():
    ours, mtp_head, blk40, blk40_start, out = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
    ver, kvs, tensors, data_start, align = read_header(ours)
    _, _, mtp_tensors, _, _ = read_header(mtp_head)
    assert align == ALIGN, align
    arch = 'qwen35moe'
    new_kvs = []
    for key, t, raw in kvs:
        if key == arch + '.block_count':
            assert t == 4
            new_kvs.append(kv_u32(key, 41))
        else:
            new_kvs.append(raw)
    assert not any(k == arch + '.nextn_predict_layers' for k, _, _ in kvs)
    new_kvs.append(kv_u32(arch + '.nextn_predict_layers', 1))

    ours_data_len = os.path.getsize(ours) - data_start
    base = (ours_data_len + ALIGN - 1) // ALIGN * ALIGN
    add = [t for t in mtp_tensors if t[0].startswith('blk.40.')]
    assert len(add) == 20, len(add)
    assert min(t[3] for t in add) == blk40_start
    infos = [tensor_info(*t) for t in tensors]
    for name, dims, ty, off in add:
        rel = off - blk40_start
        assert rel % ALIGN == 0
        infos.append(tensor_info(name, dims, ty, base + rel))

    with open(out, 'wb') as w:
        w.write(b'GGUF' + struct.pack('<IQQ', ver, len(tensors) + len(add), len(new_kvs)))
        for raw in new_kvs:
            w.write(raw)
        for raw in infos:
            w.write(raw)
        pad = (-w.tell()) % ALIGN
        w.write(b'\0' * pad)
        with open(ours, 'rb') as r:
            r.seek(data_start)
            shutil.copyfileobj(r, w, 64 << 20)
        w.write(b'\0' * (base - ours_data_len))
        with open(blk40, 'rb') as r:
            shutil.copyfileobj(r, w, 64 << 20)
    print('wrote', out, os.path.getsize(out), 'bytes;', len(tensors) + len(add), 'tensors')


if __name__ == '__main__':
    main()
