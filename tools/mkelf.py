#!/usr/bin/env python3
"""Wrap a PE region as a big-endian PPC ELF so llvm-objdump can disassemble it.
Usage: mkelf.py default.pe out.elf [start_va end_va]  (default: whole .text)"""
import struct, sys
pe = open(sys.argv[1], 'rb').read()
base = 0x82000000
start = int(sys.argv[3], 0) if len(sys.argv) > 3 else 0x82100000
end = int(sys.argv[4], 0) if len(sys.argv) > 4 else 0x82100000 + 0x829524
code = pe[start-base:end-base]
shstr = b'\0.text\0.shstrtab\0'
ehsz, shsz = 52, 40
off_code = ehsz; off_str = off_code + len(code); off_sh = (off_str + len(shstr) + 3) & ~3
eh = b'\x7fELF\x01\x02\x01' + b'\0'*9 + struct.pack('>HHIIIIIHHHHHH', 2, 20, 1, start, 0, off_sh, 0, ehsz, 0, 0, shsz, 3, 2)
sh0 = b'\0'*shsz
sh1 = struct.pack('>IIIIIIIIII', 1, 1, 6, start, off_code, len(code), 0, 0, 4, 0)
sh2 = struct.pack('>IIIIIIIIII', 7, 3, 0, 0, off_str, len(shstr), 0, 0, 1, 0)
out = eh + code + shstr
out += b'\0'*(off_sh-len(out)) + sh0 + sh1 + sh2
open(sys.argv[2], 'wb').write(out)
