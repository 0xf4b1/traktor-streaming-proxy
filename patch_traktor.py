import shutil

mac_os_key = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA5otUtjLv5LJmLK+Lw+TI
UzrX0j3UP493K8T2dzqE/tLMVvOvNOwUDvzomX0VpTZrXesLFpCrztdMG5p2I4M0
jTVTl6cpU8SD68WUjqlvLUYCHIGub4okQK57f5d4iTagU9FjyB2VwfA3nuuhhEpj
4ioQuYR8ENhMiMNMydITsXCFEbRgxpDRvIj24+/QthsOETtu2Ooq4U+pvidQPu5l
rcZdgemPUFPtTn4GqQ0/wZpaD2mzMlLUi4xlqcGo0LsCtTkPtAhSWxWrl+ReKj+k
9zJCK8qzeYUPf/fuA5I7owuyRrfN6ReiFdU/UF38Ou6pSrRCvVkQkmpTmv8kEnvn
RwIDAQAB
-----END PUBLIC KEY-----"""

windows_key = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAozFFb+t0RSB1AdDBwap+
dZ/8AH/FseqpSkG8oW3rzonQ/jEMtsY6AJogv+HfFclnrVlx1aYyJvQXIwIBx+Sk
E5J+YTdUKYlLX62xL44TQLDOw1varMnxWfCX4ih5taXDWacu1HemI+peRtsi8r9m
FVCBMFuFVOCv9vGL8H4L/12GTO0+rIIpBZr11pQ/K44WFyr9GOVx/GTeDH52Ktlx
CgOMADfgdH9hjLryS+EN/LL/yg1bw7OF9UmpZGzUaTjn1qYErlq5bqlDcBFSdo6v
b5v74acNV8Qjbov8okSoUd13A6JJkJp4Sxi/Ve07DTvPZHGIZn01nVpLX9tkDRcT
2wIDAQAB
-----END PUBLIC KEY-----"""

# read file path of binary
traktor_file_path = input("Input Traktor.exe path (e.g. 'Traktor.exe'): ")
traktor_file_backup_path = traktor_file_path + ".backup"

# create backup of the binary
shutil.copyfile(traktor_file_path, traktor_file_backup_path)

# convert strings to bytes
search_bytes = windows_key.encode('utf-8')
replace_bytes = mac_os_key.encode('utf-8')

# ensure replacement is the same length to avoid offset issues
if len(search_bytes) != len(replace_bytes):
    raise ValueError("The lengths of the keys are different. Ensure they have the same length!")

# read the binary content
with open(traktor_file_path, 'rb') as f:
    data = f.read()

# find the first occurrence of the windows key
index = data.find(search_bytes)
if index == -1:
    raise ValueError("Windows key was not found in the binary. Ensure Windows key and binary is correct!")

# replace the first occurrence of the windows key with the mac key
modified_data = data[:index] + replace_bytes + data[index + len(search_bytes):]

# write the modified binary
with open(traktor_file_path, 'wb') as f:
    f.write(modified_data)

print(f"{traktor_file_path} was patched successfully! Copy it back to the program directory to run it.")