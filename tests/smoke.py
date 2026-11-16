import json, os, urllib.request, urllib.error
BASE=os.environ.get('BASE_URL','http://localhost:8080')
count=0
def call(path, data=None, status=200):
 global count
 req=urllib.request.Request(BASE+path, data=json.dumps(data).encode() if data is not None else None,headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=5) as r: code,body=r.status,json.load(r)
 except urllib.error.HTTPError as e:code,body=e.code,json.load(e)
 assert code==status,(path,code,body)
 count+=1
 return body
assert call('/health')['status']=='ok'
members=[{'user_id':f'u{i}','username':f'User {i}','is_active':i!=4} for i in range(1,5)]
team={'team_name':'backend','members':members}
call('/team/add',team,201)
call('/team/add',team,400)
pr={'pull_request_id':'pr1','pull_request_name':'Search','author_id':'u1'}
r=call('/pullRequest/create',pr,201)['pr'];assert set(r['assigned_reviewers'])=={'u2','u3'}
call('/pullRequest/create',pr,409)
assert call('/users/getReview?user_id=u2')['pull_requests'][0]['pull_request_id']=='pr1'
assert call('/users/getReview?user_id=u4')['pull_requests']==[]
call('/users/getReview?user_id=missing',status=404)
call('/pullRequest/create',{**pr,'pull_request_id':'bad','author_id':'missing'},404)
call('/pullRequest/create',{'pull_request_id':'x'},400)
for size in (1,2):
 name='team'+str(size)
 call('/team/add',{'team_name':name,'members':[{'user_id':name+str(i),'username':'User','is_active':True} for i in range(size)]},201)
 result=call('/pullRequest/create',{'pull_request_id':name,'pull_request_name':'test','author_id':name+'0'},201)
 assert len(result['pr']['assigned_reviewers'])==size-1
# Invalid second member must not commit the first member.
call('/team/add',{'team_name':'atomic','members':[{'user_id':'a','username':'A','is_active':True},{'user_id':'b'}]},400)
call('/users/getReview?user_id=a',status=404)
print(f'PASS: {count} HTTP checks, including 0/1/2 reviewers and atomic validation')
