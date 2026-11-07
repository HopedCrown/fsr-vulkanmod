import struct, sys, zipfile

# Minimal constant-pool-aware .class parser. Not a decompiler — just enough
# to recover class/field/method names and descriptors, which is what we
# actually need to fix the mixin target names.

CONSTANT_TAGS = {
    7: 'Class', 9: 'Fieldref', 10: 'Methodref', 11: 'InterfaceMethodref',
    8: 'String', 3: 'Integer', 4: 'Float', 5: 'Long', 6: 'Double',
    12: 'NameAndType', 1: 'Utf8', 15: 'MethodHandle', 16: 'MethodType',
    17: 'Dynamic', 18: 'InvokeDynamic', 19: 'Module', 20: 'Package',
}

def parse_class(data):
    off = 0
    def u1():
        nonlocal off
        v = data[off]; off += 1; return v
    def u2():
        nonlocal off
        v = struct.unpack_from('>H', data, off)[0]; off += 2; return v
    def u4():
        nonlocal off
        v = struct.unpack_from('>I', data, off)[0]; off += 4; return v

    magic = u4()
    minor = u2(); major = u2()
    cp_count = u2()
    cp = [None]  # index 0 unused
    i = 1
    while i < cp_count:
        tag = u1()
        name = CONSTANT_TAGS.get(tag, f'Unknown({tag})')
        if tag == 1:  # Utf8
            length = u2()
            s = data[off:off+length].decode('utf-8', errors='replace')
            off += length
            cp.append(('Utf8', s))
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            idx = u2()
            cp.append((name, idx))
        elif tag in (9, 10, 11, 12, 17, 18):  # ref types, NameAndType, Dynamic, InvokeDynamic
            a = u2(); b = u2()
            cp.append((name, a, b))
        elif tag == 15:  # MethodHandle
            kind = u1(); idx = u2()
            cp.append((name, kind, idx))
        elif tag in (3, 4):  # Integer, Float
            v = u4()
            cp.append((name, v))
        elif tag in (5, 6):  # Long, Double take 2 slots
            hi = u4(); lo = u4()
            cp.append((name, hi, lo))
            cp.append(None)
            i += 1
        else:
            raise ValueError(f'Unknown constant tag {tag} at index {i}')
        i += 1

    def utf8(idx):
        entry = cp[idx]
        if entry and entry[0] == 'Utf8':
            return entry[1]
        return None

    def class_name(idx):
        entry = cp[idx]
        if entry and entry[0] == 'Class':
            return utf8(entry[1])
        return None

    access_flags = u2()
    this_class = u2()
    super_class = u2()
    interfaces_count = u2()
    off += 2 * interfaces_count  # skip interface indices

    fields = []
    fields_count = u2()
    for _ in range(fields_count):
        facc = u2()
        fname_idx = u2()
        fdesc_idx = u2()
        fname = utf8(fname_idx)
        fdesc = utf8(fdesc_idx)
        attr_count = u2()
        for _ in range(attr_count):
            off += 2  # attribute_name_index
            alen = u4()
            off += alen
        fields.append((facc, fname, fdesc))

    methods = []
    methods_count = u2()
    for _ in range(methods_count):
        macc = u2()
        name_idx = u2()
        desc_idx = u2()
        mname = utf8(name_idx)
        mdesc = utf8(desc_idx)
        attr_count = u2()
        for _ in range(attr_count):
            off += 2
            alen = u4()
            off += alen
        methods.append((macc, mname, mdesc))

    return {
        'this_class': class_name(this_class),
        'super_class': class_name(super_class),
        'fields': fields,
        'methods': methods,
    }

ACC_FLAGS = [
    (0x0001, 'public'), (0x0002, 'private'), (0x0004, 'protected'),
    (0x0008, 'static'), (0x0010, 'final'), (0x0400, 'abstract'),
]

def flags_str(flags):
    return ' '.join(name for bit, name in ACC_FLAGS if flags & bit)

def dump_entry(jar_path, entry_name):
    with zipfile.ZipFile(jar_path) as z:
        data = z.read(entry_name)
    info = parse_class(data)
    print(f"=== {info['this_class']} extends {info['super_class']} ===")
    print("-- fields --")
    for facc, fname, fdesc in info['fields']:
        print(f"  {flags_str(facc):30s} {fname} : {fdesc}")
    print("-- methods --")
    for macc, mname, mdesc in info['methods']:
        print(f"  {flags_str(macc):30s} {mname}{mdesc}")

def dump_refs(jar_path, entry_name):
    # Prints every Methodref/Fieldref the class references, resolved to
    # "OwnerClass.name:descriptor" — this approximates a call graph without
    # needing to disassemble bytecode, which this minimal parser doesn't do.
    with zipfile.ZipFile(jar_path) as z:
        data = z.read(entry_name)
    off = 0
    def u1():
        nonlocal off
        v = data[off]; off += 1; return v
    def u2():
        nonlocal off
        v = struct.unpack_from('>H', data, off)[0]; off += 2; return v
    def u4():
        nonlocal off
        v = struct.unpack_from('>I', data, off)[0]; off += 4; return v
    u4(); u2(); u2()
    cp_count = u2()
    cp = [None]
    i = 1
    while i < cp_count:
        tag = u1()
        if tag == 1:
            length = u2()
            s = data[off:off+length].decode('utf-8', errors='replace')
            off += length
            cp.append(('Utf8', s))
        elif tag in (7, 8, 16, 19, 20):
            idx = u2(); cp.append((CONSTANT_TAGS[tag], idx))
        elif tag in (9, 10, 11, 12, 17, 18):
            a = u2(); b = u2(); cp.append((CONSTANT_TAGS[tag], a, b))
        elif tag == 15:
            kind = u1(); idx = u2(); cp.append((CONSTANT_TAGS[tag], kind, idx))
        elif tag in (3, 4):
            v = u4(); cp.append((CONSTANT_TAGS[tag], v))
        elif tag in (5, 6):
            hi = u4(); lo = u4(); cp.append((CONSTANT_TAGS[tag], hi, lo)); cp.append(None); i += 1
        else:
            raise ValueError(f'Unknown tag {tag}')
        i += 1

    def utf8(idx):
        e = cp[idx]
        return e[1] if e and e[0] == 'Utf8' else None
    def cls(idx):
        e = cp[idx]
        return utf8(e[1]) if e and e[0] == 'Class' else None
    def nat(idx):
        e = cp[idx]
        if e and e[0] == 'NameAndType':
            return utf8(e[1]), utf8(e[2])
        return None, None

    seen = set()
    for idx, e in enumerate(cp):
        if e and e[0] in ('Methodref', 'InterfaceMethodref', 'Fieldref'):
            owner = cls(e[1])
            name, desc = nat(e[2])
            line = f"{e[0]:20s} {owner}.{name} : {desc}"
            if line not in seen:
                seen.add(line)
                print(" ", line)

if __name__ == '__main__':
    jar_path = sys.argv[1]
    entry_name = sys.argv[2]
    mode = sys.argv[3] if len(sys.argv) > 3 else 'methods'
    if mode == 'refs':
        dump_refs(jar_path, entry_name)
    else:
        dump_entry(jar_path, entry_name)
