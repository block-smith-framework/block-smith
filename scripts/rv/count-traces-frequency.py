#!/usr/bin/env python3

import os
import sys


def generate(traces_dir):
    id_to_trace = {}
    output = ['=== UNIQUE TRACES WITH ID ===\n']
    
    with open(os.path.join(traces_dir, 'traces-id.txt')) as f:
        header = False
        for line in f.readlines():
            line = line.strip()
            if not header or not line:
                header = True
                continue
            id, _, trace = line.partition(' ')
            id_to_trace[id] = trace
    
    with open(os.path.join(traces_dir, 'specs-frequency.csv')) as f:
        for line in f.readlines():
            line = line.strip()
            if not line or line == 'OK':
                continue
        
            id, _, spec_to_freq = line.partition(' ')
            if len(spec_to_freq) <= 2:
                print('Error processing spec ID: {}'.format(id))
                continue
        
            total_freq = 0
            spec_to_freq = spec_to_freq[1:-1]
            for spec_str in spec_to_freq.split(', '):
                spec, freq = spec_str.split('=')
                total_freq += int(freq)
            
            output.append('{} {} {}\n'.format(id, total_freq, id_to_trace[id]))
    
    # unique-traces.txt file format: trace-id trace-frequency trace
    with open(os.path.join(traces_dir, 'unique-traces.txt'), 'w') as f:
        f.writelines(output)


def main(argv=None):
    argv = argv or sys.argv
    
    if len(argv) < 2:
        print('Usage: python3 count-traces-frequency.py <traces-dir>')
        exit(1)
    traces_dir = argv[1]
    
    if not os.path.exists(os.path.join(traces_dir, 'specs-frequency.csv')):
        print('Cannot find specs-frequency.csv')
        exit(1)
    
    if not os.path.exists(os.path.join(traces_dir, 'traces-id.txt')):
        print('Cannot find unique-traces.txt')
        exit(1)
        
    generate(traces_dir)
    
    
if __name__ == '__main__':
    main()
