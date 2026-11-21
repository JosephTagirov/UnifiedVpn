"""Generate the shared Android/JVM service policy from the checked public registry dataset."""
from pathlib import Path
import argparse,base64,gzip,hashlib,io,json

REPO=Path(__file__).resolve().parent.parent

def rendered(data):
    rules=data['direct_domains']
    if not(28<=len(rules)<=131072 and len(rules)==len(set(rules))):
        raise ValueError('Invalid or duplicate domain rules')
    if data['direct_ip_cidrs']:
        raise ValueError('This policy intentionally contains domains, not shared IP ranges')
    if not all(isinstance(rule,str)and rule.startswith(('domain:','full:','regexp:'))and len(rule)<512 and not any(ord(c)<32 for c in rule)for rule in rules):
        raise ValueError('Invalid domain matcher')
    if set(rules)&{'domain:ru','domain:su','domain:xn--p1ai','domain:com','domain:net','regexp:.*'}:
        raise ValueError('A public top-level suffix is not a service allowlist')
    canonical=json.dumps(data,ensure_ascii=False,indent=2)+'\n'
    digest=hashlib.sha256(canonical.encode()).hexdigest()
    stream=io.BytesIO()
    with gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0,compresslevel=9)as archive:
        archive.write(json.dumps(rules,ensure_ascii=True,separators=(',',':')).encode())
    encoded=base64.b64encode(stream.getvalue()).decode()
    def quote(value):return json.dumps(value,ensure_ascii=True).replace('$',r'\$')
    lines=['package org.olcbox.app.vpn','',
        'import java.io.ByteArrayInputStream','import java.util.zip.GZIPInputStream','import kotlin.io.encoding.Base64',
        'import kotlinx.serialization.json.Json','import kotlinx.serialization.json.jsonArray','import kotlinx.serialization.json.jsonPrimitive','',
        '/** Generated from tools/client-direct-policy.json; run tools/GenerateClientDirectPolicy.py to update. */',
        'internal object ClientDirectPolicy {',
        '    fun effectiveDomains(profile: org.olcbox.app.data.model.VpnProfileConfig? = null): List<String> =',
        '        profile?.routingPolicy?.normalizedOrNull()?.directDomains ?: domainRules',
        '', f'    const val SHA256 = "{digest}"',
        '    val domainRules: List<String> by lazy {',
        '        val compressed = Base64.decode(parts.joinToString(""))',
        '        val text = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes().toString(Charsets.UTF_8) }',
        '        Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }','    }',
        '    private val parts = arrayOf(']
    lines+=['        '+quote(encoded[index:index+8192])+','for index in range(0,len(encoded),8192)]
    lines+=['    )','}']
    return canonical,'\n'.join(lines)+'\n',digest,len(rules)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,default=REPO/'tools/client-direct-policy.json')
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    canonical,source,digest,count=rendered(json.loads(args.input.read_text(encoding='utf-8')))
    data_path=REPO/'tools/client-direct-policy.json'
    kotlin_path=REPO/'sharedUI/src/jvmAndAndroidMain/kotlin/org/olcbox/app/vpn/ClientDirectPolicy.kt'
    if args.check:
        if data_path.read_text(encoding='utf-8')!=canonical or kotlin_path.read_text(encoding='utf-8')!=source:
            raise SystemExit('Generated direct policy is stale')
    else:
        data_path.write_text(canonical,encoding='utf-8');kotlin_path.write_text(source,encoding='utf-8')
    print(json.dumps({'rules':count,'policySHA256':digest,'checked':args.check}))

if __name__=='__main__':main()
