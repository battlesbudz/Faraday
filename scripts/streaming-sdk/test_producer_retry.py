from datetime import datetime, timedelta, timezone
import unittest
from producer_identity import select_producer, workflow_identity, PRODUCERS, KINDS
from artifacts import ArtifactError

SHA='a'*40
RUN=123

def iso(seconds):
    return (datetime(2026,10,6,19,tzinfo=timezone.utc)+timedelta(seconds=seconds)).isoformat()

def manifest():
    return {'repository':'owner/repo','run':{'id':RUN,'head_sha':SHA,'attempt':2},
        'jobs':[{'name':name,'attempt':a,'run_id':RUN,'head_sha':SHA,'status':'completed',
            'conclusion':'success','started_at':iso(0),'completed_at':iso(100)}
            for name in PRODUCERS.values() for a in (1,2)],
        'artifacts':[{'id':i,'name':f'{prefix}-123-1','workflow_run':{'id':RUN,'head_sha':SHA},
            'expired':False,'created_at':iso(90),
            'archive_download_url':f'https://api.github.com/repos/owner/repo/actions/artifacts/{i}/zip'}
            for i,prefix in enumerate(KINDS.values(),100)]}


def latest(data,kind):
    return next(j for j in data['jobs'] if j['name']==PRODUCERS[kind] and j['attempt']==2)


class SuccessfulProducerRetry(unittest.TestCase):
    def choose(self, data, kind='sdk', **changes):
        args={'repository':'owner/repo','run_id':'123','head_sha':SHA,'kind':kind,
            'artifact_name':f'{KINDS[kind]}-123-1','artifact_id':'100' if kind=='sdk' else '101',
            'producer_attempt':'1','current_attempt':'2'}
        args.update(changes)
        return select_producer(data,**args)

    def test_untouched_attempt1_producers_serve_attempt2_consumer(self):
        data=manifest()
        self.assertEqual(100,self.choose(data)['id'])
        self.assertEqual(101,self.choose(data,'quality')['id'])
        current={'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'2','GITHUB_SHA':SHA,'GITHUB_REPOSITORY':'owner/repo'}
        self.assertEqual(dict(current,GITHUB_RUN_ATTEMPT='1'),workflow_identity(current,'1'))
        self.assertEqual(current,workflow_identity(current))

    def test_independent_quality_retry_preserves_original_sdk(self):
        data=manifest();latest(data,'quality').update(started_at=iso(200),completed_at=iso(300))
        q=data['artifacts'][1];q.update(id=102,name=f'{KINDS["quality"]}-123-2',created_at=iso(290),
            archive_download_url='https://api.github.com/repos/owner/repo/actions/artifacts/102/zip')
        self.assertEqual(100,self.choose(data,current_attempt='3')['id'])
        self.assertEqual(102,self.choose(data,'quality',current_attempt='3',producer_attempt='2',
            artifact_name=q['name'],artifact_id='102')['id'])
        with self.assertRaises(ArtifactError):self.choose(data,'quality')

    def test_new_failed_producer_cannot_fallback_to_previous_success(self):
        for kind in KINDS:
            data=manifest();latest(data,kind).update(conclusion='failure',started_at=iso(200),completed_at=iso(300))
            with self.subTest(kind=kind),self.assertRaisesRegex(ArtifactError,'Latest producer did not succeed'):
                self.choose(data,kind)

    def test_new_successful_producer_rejects_stale_old_artifact(self):
        for kind in KINDS:
            data=manifest();latest(data,kind).update(started_at=iso(200),completed_at=iso(300))
            with self.subTest(kind=kind),self.assertRaisesRegex(ArtifactError,'Missing current producer artifact'):
                self.choose(data,kind)

    def test_quality_cannot_borrow_sdk_job_identity(self):
        data=manifest();data['jobs']=[j for j in data['jobs'] if j['name']==PRODUCERS['sdk']]
        with self.assertRaisesRegex(ArtifactError,'Missing producer job'):self.choose(data,'quality')

    def test_wrong_retained_id_or_attempt_name_rejected(self):
        for kind in KINDS:
            for change in ({'artifact_id':'999'},{'producer_attempt':'2'},
                           {'artifact_name':f'{KINDS[kind]}-123-2'},{'artifact_id':''}):
                with self.subTest(kind=kind,change=change),self.assertRaises(ValueError):self.choose(manifest(),kind,**change)

    def test_foreign_run_source_repository_and_producer_rejected(self):
        for change in ({'run_id':'124'},{'head_sha':'b'*40},{'repository':'other/repo'}):
            with self.subTest(change=change),self.assertRaises(ValueError):self.choose(manifest(),**change)
        data=manifest();latest(data,'sdk')['head_sha']='b'*40
        with self.assertRaisesRegex(ArtifactError,'Foreign producer'):self.choose(data)

    def test_artifact_from_another_run_source_or_expired_is_rejected(self):
        for change in ({'workflow_run':{'id':124,'head_sha':SHA}},
                       {'workflow_run':{'id':RUN,'head_sha':'b'*40}},{'expired':True}):
            data=manifest();data['artifacts'][0].update(change)
            with self.subTest(change=change),self.assertRaises(ArtifactError):self.choose(data)

    def test_invalid_or_future_attempt_fails_closed(self):
        current={'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'2','GITHUB_SHA':SHA,'GITHUB_REPOSITORY':'owner/repo'}
        for attempt in ('','0','3','-1','01','not-an-attempt'):
            with self.subTest(attempt=attempt),self.assertRaises(ValueError):workflow_identity(current,attempt)


if __name__=='__main__':unittest.main()
