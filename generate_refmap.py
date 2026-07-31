#!/usr/bin/env python3
"""Generate flashback.refmap.json from Mojang mapping file and mixin source files."""

import json
import os
import re

MAPPINGS_FILE = "/Users/riku/Documents/curseforge/minecraft/Install/libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-mappings.txt"
MIXIN_SRC_DIR = "src/main/java/com/moulberry/flashback/mixin"
OUTPUT_FILE = "src/main/resources/flashback.refmap.json"

# Java descriptor to simple name mapping
PRIMITIVE_MAP = {
    'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double',
    'Z': 'boolean', 'B': 'byte', 'C': 'char', 'S': 'short', 'V': 'void'
}


def parse_mappings(filepath):
    """Parse the Mojang->SRG mapping file.
    Returns dict: {className: {methodDescriptor: srgName}}
    methodDescriptor: "methodName(param1,param2)"
    """
    mappings = {}
    current_class = None
    
    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            
            class_match = re.match(r'^(.+?)\s*->\s*(\S+):$', line)
            if class_match:
                current_class = class_match.group(1)
                if current_class not in mappings:
                    mappings[current_class] = {}
                mappings[current_class]['__srg_name__'] = class_match.group(2)
                continue
            
            if not current_class:
                continue
            
            method_match = re.match(r'^\d+:\d+:\S+\s+(\S+)\((.*?)\)\s*->\s*(\S+)$', line)
            if method_match:
                method_name = method_match.group(1)
                params = method_match.group(2)
                srg_name = method_match.group(3)
                
                if method_name != '<init>':
                    key = f"{method_name}({params})"
                    mappings[current_class][key] = srg_name
                continue
            
            field_match = re.match(r'^\S+\s+(\S+)\s*->\s*(\S+)$', line)
            if field_match:
                field_name = field_match.group(1)
                srg_name = field_match.group(2)
                mappings[current_class][field_name] = srg_name
                continue
    
    return mappings


def desc_to_name(desc):
    """Convert single Java descriptor to simple name.
    'Lnet/minecraft/.../Class;' -> 'net.minecraft....Class'
    'I' -> 'int', 'D' -> 'double', etc.
    '[I' -> 'int[]', etc.
    """
    if desc in PRIMITIVE_MAP:
        return PRIMITIVE_MAP[desc]
    
    if desc.startswith('['):
        array_depth = 0
        i = 0
        while i < len(desc) and desc[i] == '[':
            array_depth += 1
            i += 1
        inner = desc_to_name(desc[i:])
        return inner + ('[]' * array_depth)
    
    if desc.startswith('L') and desc.endswith(';'):
        return desc[1:-1].replace('/', '.')
    
    return desc  # Unknown format, return as-is


def parse_method_target(target_str):
    """Parse a Mixin target string:
    'Lpath/Class;method(Lparam1;Lparam2;)RetType'
    Returns (className, methodName, paramsKey) or None
    paramsKey: "param1,param2" (simple Java names)
    """
    match = re.match(r'L([^;]+);([^(]+)\(([^)]*)\)(.+)', target_str)
    if not match:
        return None
    
    class_name = match.group(1).replace('/', '.')
    method_name = match.group(2)
    params_str = match.group(3)
    
    params = []
    if params_str:
        i = 0
        while i < len(params_str):
            c = params_str[i]
            if c == 'L':
                end = params_str.index(';', i)
                params.append(desc_to_name(params_str[i:end+1]))
                i = end + 1
            elif c == '[':
                start = i
                while i < len(params_str) and params_str[i] == '[':
                    i += 1
                if params_str[i] == 'L':
                    end = params_str.index(';', i) + 1
                else:
                    end = i + 1
                params.append(desc_to_name(params_str[start:end]))
                i = end
            else:
                params.append(desc_to_name(c))
                i += 1
    
    params_key = ','.join(params)
    return class_name, method_name, params_key


def extract_mixin_targets(src_dir):
    """Extract method targets from mixin Java files."""
    targets = []
    
    for root, dirs, files in os.walk(src_dir):
        for filename in files:
            if not filename.endswith('.java'):
                continue
            filepath = os.path.join(root, filename)
            with open(filepath, 'r') as f:
                content = f.read()
            
            for match in re.finditer(r'target\s*=\s*"([^"]+)"', content):
                target_str = match.group(1)
                result = parse_method_target(target_str)
                if result:
                    targets.append(result)
    
    return targets


def generate_refmap(mappings, targets):
    """Generate the refmap JSON in the format Mixin expects.
    Key: method name (without descriptor, for non-overloaded methods)
    """
    refmap = {"mappings": {}}
    found = 0
    missed = 0
    
    for class_name, method_name, params_key in targets:
        if class_name not in mappings:
            if not class_name.startswith('org.') and not class_name.startswith('java.'):
                print(f"WARNING: Class {class_name} not found in mappings")
            missed += 1
            continue
        
        class_mappings = mappings[class_name]
        key = f"{method_name}({params_key})"
        
        srg_name = class_mappings.get(key)
        if srg_name is None:
            print(f"WARNING: Method {class_name}.{key} not found in mappings")
            missed += 1
            continue
        
        srg_class_path = class_name.replace('.', '/')
        
        if srg_class_path not in refmap["mappings"]:
            refmap["mappings"][srg_class_path] = {}
        
        # Use just method name as key (Mixin resolves by class + name)
        refmap["mappings"][srg_class_path][method_name] = srg_name
        found += 1
    
    print(f"Found {found} mappings, missed {missed}")
    return refmap


def main():
    print("Parsing mappings...")
    mappings = parse_mappings(MAPPINGS_FILE)
    print(f"Parsed {len(mappings)} classes from mappings")
    
    print("Extracting mixin targets...")
    targets = extract_mixin_targets(MIXIN_SRC_DIR)
    print(f"Extracted {len(targets)} unique targets")
    
    print("Generating refmap...")
    refmap = generate_refmap(mappings, targets)
    
    print(f"Writing refmap to {OUTPUT_FILE}...")
    with open(OUTPUT_FILE, 'w') as f:
        json.dump(refmap, f, indent=2)
    
    count = len(refmap['mappings'])
    print(f"Done! Generated {count} class mappings")


if __name__ == '__main__':
    main()
